package com.lody.virtual.client.hook.proxies.keystore;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.os.VUserInfo;
import com.lody.virtual.os.VUserManager;

import org.apptwin.custodian.contract.CustodianContract;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Isolates Android Keystore aliases that would otherwise be shared by every guest under the host
 * UID. Keystore2 uses app-domain descriptors, so the physical alias is namespaced at the Binder
 * boundary and converted back before guest code observes it.
 */
public final class KeystoreStub extends BinderInvocationProxy {
    private static final String TAG = "KeystoreStub";
    private static final String SERVICE_NAME =
            "android.system.keystore2.IKeystoreService/default";
    private static final String DESCRIPTOR_CLASS = "android.system.keystore2.KeyDescriptor";
    private static final String SECURITY_LEVEL_INTERFACE =
            "android.system.keystore2.IKeystoreSecurityLevel";
    private static final String OPERATION_INTERFACE =
            "android.system.keystore2.IKeystoreOperation";

    public KeystoreStub() {
        super(loadStubClass("android.system.keystore2.IKeystoreService$Stub"), SERVICE_NAME);
    }

    @Override
    public void inject() throws Throwable {
        super.inject();
        VLog.i(TAG, "keystore2 hook injected base=%s proxy=%s",
                getInvocationStub().getBaseBinder() != null,
                getInvocationStub().getProxyInterface() != null);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new GetSecurityLevel());
        addMethodProxy(new ListEntries());
        addMethodProxy(new GetKeyEntry());
        for (String method : new String[]{
                "updateSubcomponent", "deleteKey", "grant", "ungrant"
        }) {
            addMethodProxy(new DescriptorMethod(method));
        }
    }

    private static final class GetKeyEntry extends MethodProxy {
        @Override
        public String getMethodName() {
            return "getKeyEntry";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            Owner owner = currentOwner();
            FacebookLiteAttestationKeyCompat.ensure(owner.packageName, owner.userId);
            List<AliasMutation> mutations = rewriteArguments(owner, args);
            try {
                Object result = method.invoke(who, args);
                Object physicalDescriptor = firstOwnedDescriptor(owner, args);
                if (physicalDescriptor != null
                        && KeystoreAuthorizationMetadata.evaluate(
                                result, getHostContext())
                        == KeystoreAuthorizationPolicy.Decision.DELETE_PERMANENTLY_INVALID
                        && deletePhysicalKey(who, method, physicalDescriptor)) {
                    VLog.w(TAG,
                            "removed permanently invalid guest key package=%s user=%d",
                            owner.packageName, owner.userId);
                    // Re-read so the framework observes KEY_NOT_FOUND and lets the app regenerate
                    // its own key with the current lock-screen and biometric authenticator IDs.
                    Object regenerated = method.invoke(who, args);
                    wrapSecurityLevels(regenerated);
                    return regenerated;
                }
                // Keep the physical descriptor inside AndroidKeyStore's private key object. The
                // framework reuses it for createOperation; converting it back to the guest alias
                // makes Keystore2 return KEY_NOT_FOUND (reported as a permanently invalid key).
                // Public alias enumeration is still converted by ListEntries below.
                wrapSecurityLevels(result);
                return result;
            } finally {
                restoreMutations(mutations);
            }
        }
    }

    private static Class<?> loadStubClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Keystore2 Binder is unavailable", e);
        }
    }

    private static Owner currentOwner() {
        String packageName = VClientImpl.get().getCurrentPackage();
        int userId = VUserHandle.getUserId(VClientImpl.get().getVUid());
        if (!CustodianKeyOwnerPolicy.requiresCustodian(packageName)) {
            return new Owner(packageName, userId, null);
        }
        CustodianKeyspaceState.Record state = CustodianKeyspaceState.readForUser(userId);
        if (state == null) {
            return new Owner(packageName, userId, null);
        }
        VUserInfo user = VUserManager.get().getUserInfo(userId);
        String stableOwner = user == null
                ? null
                : CustodianKeyOwnerPolicy.stableOwnerId(packageName, user.name);
        if (!state.ownerSpaceId.equals(stableOwner)) {
            throw new IllegalStateException("Custodian Space ownership is inconsistent");
        }
        int signature = com.lody.virtual.client.core.VirtualCore.get()
                .getUnHookPackageManager()
                .checkSignatures(CustodianContract.HOST_PACKAGE, CustodianContract.CUSTODIAN_PACKAGE);
        if (signature != android.content.pm.PackageManager.SIGNATURE_MATCH) {
            throw new IllegalStateException("Custodian is missing or untrusted");
        }
        return new Owner(packageName, userId, state.keyspaceId);
    }

    private static final class Owner {
        final String packageName;
        final int userId;
        final String custodianKeyspaceId;

        Owner(String packageName, int userId, String custodianKeyspaceId) {
            this.packageName = packageName;
            this.userId = userId;
            this.custodianKeyspaceId = custodianKeyspaceId;
        }

        String toPhysicalAlias(String guestAlias) {
            return custodianKeyspaceId == null
                    ? KeystoreAliasPolicy.toPhysicalAlias(packageName, userId, guestAlias)
                    : CustodianAliasPolicy.toPhysicalAlias(
                            custodianKeyspaceId, packageName, guestAlias);
        }

        String toGuestAlias(String physicalAlias) {
            return custodianKeyspaceId == null
                    ? KeystoreAliasPolicy.toGuestAlias(packageName, userId, physicalAlias)
                    : CustodianAliasPolicy.toGuestAlias(
                            custodianKeyspaceId, packageName, physicalAlias);
        }

        boolean owns(String physicalAlias) {
            return custodianKeyspaceId == null
                    ? KeystoreAliasPolicy.isOwnedBy(packageName, userId, physicalAlias)
                    : CustodianAliasPolicy.isOwnedBy(
                            custodianKeyspaceId, packageName, physicalAlias);
        }
    }

    private static final class AliasMutation {
        final Object descriptor;
        final Field aliasField;
        final String originalAlias;

        AliasMutation(Object descriptor, Field aliasField, String originalAlias) {
            this.descriptor = descriptor;
            this.aliasField = aliasField;
            this.originalAlias = originalAlias;
        }

        void restore() throws IllegalAccessException {
            aliasField.set(descriptor, originalAlias);
        }
    }

    private static final class DescriptorMethod extends MethodProxy {
        private final String methodName;

        DescriptorMethod(String methodName) {
            this.methodName = methodName;
        }

        @Override
        public String getMethodName() {
            return methodName;
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            Owner owner = currentOwner();
            Object originalDescriptor = firstDescriptor(args);
            String originalAlias = descriptorAlias(originalDescriptor);
            boolean refreshFacebookAttestation = "generateKey".equals(methodName)
                    && FacebookLiteAttestationKeyCompat.isAttestationAlias(
                            owner.packageName, originalAlias);
            List<AliasMutation> mutations = rewriteArguments(owner, args);
            try {
                Object result = method.invoke(who, args);
                rewriteResultForGuest(owner, result);
                if (refreshFacebookAttestation) {
                    FacebookLiteAttestationKeyCompat.refreshWarmupAfterGeneration(
                            owner.packageName);
                }
                return result;
            } finally {
                restoreMutations(mutations);
            }
        }
    }

    private static final class CreateOperation extends MethodProxy {
        @Override
        public String getMethodName() {
            return "createOperation";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            Owner owner = currentOwner();
            List<AliasMutation> mutations = rewriteArguments(owner, args);
            try {
                Object physicalDescriptor = firstOwnedDescriptor(owner, args);
                try {
                    Object result = method.invoke(who, args);
                    return wrapOperationResult(
                            owner, who, method, physicalDescriptor, result);
                } catch (InvocationTargetException error) {
                    if (physicalDescriptor != null
                            && KeystoreResponsePolicy.requiresKeyReset(error.getCause())
                            && deleteOwnedGuestKey(
                                    owner, who, method, physicalDescriptor)) {
                        VLog.w(TAG,
                                "removed daemon-invalidated guest key package=%s user=%d",
                                owner.packageName, owner.userId);
                    }
                    throw error;
                }
            } finally {
                restoreMutations(mutations);
            }
        }
    }

    private static final class OperationMethod extends MethodProxy {
        private final String methodName;
        private final Owner owner;
        private final Object securityLevel;
        private final Method createOperation;
        private final Object descriptor;

        OperationMethod(String methodName, Owner owner, Object securityLevel,
                        Method createOperation, Object descriptor) {
            this.methodName = methodName;
            this.owner = owner;
            this.securityLevel = securityLevel;
            this.createOperation = createOperation;
            this.descriptor = descriptor;
        }

        @Override
        public String getMethodName() {
            return methodName;
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            try {
                return method.invoke(who, args);
            } catch (InvocationTargetException error) {
                if (KeystoreResponsePolicy.requiresKeyReset(error.getCause())
                        && deleteOwnedGuestKey(
                                owner, securityLevel, createOperation, descriptor)) {
                    VLog.w(TAG,
                            "removed operation-invalidated guest key package=%s user=%d",
                            owner.packageName, owner.userId);
                }
                throw error;
            }
        }
    }

    private static final class GetSecurityLevel extends MethodProxy {
        @Override
        public String getMethodName() {
            return "getSecurityLevel";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return wrapSecurityLevel(method.invoke(who, args));
        }
    }

    private static final class ListEntries extends MethodProxy {
        @Override
        public String getMethodName() {
            return "listEntries";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            Owner owner = currentOwner();
            FacebookLiteAttestationKeyCompat.ensure(owner.packageName, owner.userId);
            Object result = method.invoke(who, args);
            if (result == null || !result.getClass().isArray()) return result;

            Class<?> componentType = result.getClass().getComponentType();
            List<Object> owned = new ArrayList<>();
            for (int index = 0; index < Array.getLength(result); index++) {
                Object descriptor = Array.get(result, index);
                String physicalAlias = descriptorAlias(descriptor);
                String guestAlias = owner.toGuestAlias(physicalAlias);
                if (guestAlias != null) {
                    setDescriptorAlias(descriptor, guestAlias);
                    owned.add(descriptor);
                }
            }
            Object filtered = Array.newInstance(componentType, owned.size());
            for (int index = 0; index < owned.size(); index++) {
                Array.set(filtered, index, owned.get(index));
            }
            return filtered;
        }
    }

    private static List<AliasMutation> rewriteArguments(Owner owner, Object[] args)
            throws ReflectiveOperationException {
        List<AliasMutation> mutations = new ArrayList<>();
        if (args == null) return mutations;
        for (Object arg : args) {
            rewriteDescriptorArgument(owner, arg, mutations);
        }
        return mutations;
    }

    private static void rewriteDescriptorArgument(Owner owner, Object value,
                                                  List<AliasMutation> mutations)
            throws ReflectiveOperationException {
        if (value == null) return;
        if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                rewriteDescriptorArgument(owner, Array.get(value, index), mutations);
            }
            return;
        }
        if (!DESCRIPTOR_CLASS.equals(value.getClass().getName())) return;
        Field domain = value.getClass().getField("domain");
        if (domain.getInt(value) != 0) return; // Domain.APP only; grant/blob descriptors are opaque.
        Field alias = value.getClass().getField("alias");
        String original = (String) alias.get(value);
        if (original == null) return;
        String physical = owner.toPhysicalAlias(original);
        if (!physical.equals(original)) {
            mutations.add(new AliasMutation(value, alias, original));
            alias.set(value, physical);
        }
    }

    private static void restoreMutations(List<AliasMutation> mutations)
            throws IllegalAccessException {
        for (int index = mutations.size() - 1; index >= 0; index--) {
            mutations.get(index).restore();
        }
    }

    private static Object firstOwnedDescriptor(Owner owner, Object[] args)
            throws ReflectiveOperationException {
        if (args == null) return null;
        for (Object arg : args) {
            Object descriptor = firstDescriptor(arg);
            if (descriptor == null) continue;
            String alias = descriptorAlias(descriptor);
            if (owner.owns(alias)) {
                return descriptor;
            }
        }
        return null;
    }

    private static Object firstDescriptor(Object value) {
        if (value == null) return null;
        if (DESCRIPTOR_CLASS.equals(value.getClass().getName())) return value;
        if (!value.getClass().isArray()) return null;
        for (int index = 0; index < Array.getLength(value); index++) {
            Object descriptor = firstDescriptor(Array.get(value, index));
            if (descriptor != null) return descriptor;
        }
        return null;
    }

    private static boolean deletePhysicalKey(Object who, Method getKeyEntry,
                                             Object descriptor) {
        try {
            Method deleteKey = getKeyEntry.getDeclaringClass()
                    .getMethod("deleteKey", descriptor.getClass());
            deleteKey.invoke(who, descriptor);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            VLog.w(TAG, "unable to remove permanently invalid guest key: %s",
                    cause.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean deleteOwnedGuestKey(Owner owner, Object who, Method sourceMethod,
                                               Object descriptor) {
        synchronized (descriptor) {
            List<AliasMutation> mutations = new ArrayList<>();
            try {
                rewriteDescriptorArgument(owner, descriptor, mutations);
                String alias = descriptorAlias(descriptor);
                return owner.owns(alias)
                        && deletePhysicalKey(who, sourceMethod, descriptor);
            } catch (ReflectiveOperationException | RuntimeException error) {
                return false;
            } finally {
                try {
                    restoreMutations(mutations);
                } catch (IllegalAccessException ignored) {
                    // The descriptor remains scoped to this guest even if restoration fails.
                }
            }
        }
    }

    private static String descriptorAlias(Object descriptor) throws ReflectiveOperationException {
        if (descriptor == null || !DESCRIPTOR_CLASS.equals(descriptor.getClass().getName())) {
            return null;
        }
        return (String) descriptor.getClass().getField("alias").get(descriptor);
    }

    private static void setDescriptorAlias(Object descriptor, String alias)
            throws ReflectiveOperationException {
        descriptor.getClass().getField("alias").set(descriptor, alias);
    }

    private static void rewriteResultForGuest(Owner owner, Object result)
            throws ReflectiveOperationException {
        if (result == null) return;
        Class<?> type = result.getClass();
        if (DESCRIPTOR_CLASS.equals(type.getName())) {
            String guestAlias = owner.toGuestAlias(descriptorAlias(result));
            if (guestAlias != null) setDescriptorAlias(result, guestAlias);
            return;
        }
        if (type.isArray()) {
            for (int index = 0; index < Array.getLength(result); index++) {
                rewriteResultForGuest(owner, Array.get(result, index));
            }
            return;
        }
        if (!type.getName().startsWith("android.system.keystore2.")) return;
        for (Field field : type.getFields()) {
            Class<?> fieldType = field.getType();
            if (shouldWrapResultField(fieldType.getName())) {
                Object securityLevel = field.get(result);
                if (securityLevel != null) {
                    field.set(result, wrapSecurityLevel(securityLevel));
                }
            } else if (DESCRIPTOR_CLASS.equals(fieldType.getName())
                    || fieldType.isArray()
                    || fieldType.getName().startsWith("android.system.keystore2.KeyMetadata")) {
                rewriteResultForGuest(owner, field.get(result));
            }
        }
    }

    private static void wrapSecurityLevels(Object result) throws ReflectiveOperationException {
        if (result == null) return;
        Class<?> type = result.getClass();
        if (type.isArray()) {
            for (int index = 0; index < Array.getLength(result); index++) {
                wrapSecurityLevels(Array.get(result, index));
            }
            return;
        }
        if (!type.getName().startsWith("android.system.keystore2.")) return;
        for (Field field : type.getFields()) {
            Class<?> fieldType = field.getType();
            if (shouldWrapResultField(fieldType.getName())) {
                Object securityLevel = field.get(result);
                if (securityLevel != null) {
                    field.set(result, wrapSecurityLevel(securityLevel));
                }
            } else if (fieldType.isArray()
                    || fieldType.getName().startsWith("android.system.keystore2.KeyMetadata")) {
                wrapSecurityLevels(field.get(result));
            }
        }
    }

    static boolean shouldWrapResultField(String fieldTypeName) {
        return SECURITY_LEVEL_INTERFACE.equals(fieldTypeName);
    }

    static boolean shouldWrapOperationField(String fieldTypeName) {
        return OPERATION_INTERFACE.equals(fieldTypeName);
    }

    private static Object wrapOperationResult(Owner owner, Object securityLevel,
                                              Method createOperation, Object descriptor,
                                              Object result) {
        if (descriptor == null || result == null) return result;
        try {
            for (Field field : result.getClass().getFields()) {
                if (!shouldWrapOperationField(field.getType().getName())) continue;
                Object operation = field.get(result);
                if (operation == null) continue;
                MethodInvocationStub<Object> stub = new MethodInvocationStub<>(operation);
                for (String name : new String[]{"updateAad", "update", "finish"}) {
                    stub.addMethodProxy(new OperationMethod(
                            name, owner, securityLevel, createOperation, descriptor));
                }
                field.set(result, stub.getProxyInterface());
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            VLog.w(TAG, "unable to wrap guest keystore operation: %s",
                    error.getClass().getSimpleName());
        }
        return result;
    }

    private static Object wrapSecurityLevel(Object securityLevel) {
        if (securityLevel == null) return null;
        MethodInvocationStub<Object> stub = new MethodInvocationStub<>(securityLevel);
        stub.addMethodProxy(new CreateOperation());
        for (String name : new String[]{
                "generateKey", "importKey", "importWrappedKey",
                "convertStorageKeyToEphemeral", "deleteKey"
        }) {
            stub.addMethodProxy(new DescriptorMethod(name));
        }
        return stub.getProxyInterface();
    }
}
