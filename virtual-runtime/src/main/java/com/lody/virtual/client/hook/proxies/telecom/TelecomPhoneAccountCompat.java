package com.lody.virtual.client.hook.proxies.telecom;

import android.content.ComponentName;
import android.os.Process;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;

/** Converts a guest LINE PhoneAccount into an identity Android can verify as the host UID. */
final class TelecomPhoneAccountCompat {
    private TelecomPhoneAccountCompat() {
    }

    static PhoneAccountHandle rewriteHandle(PhoneAccountHandle handle, String currentPackage,
                                            String hostPackage, int virtualUserId, int slot) {
        if (handle == null || handle.getComponentName() == null || hostPackage == null) {
            return handle;
        }
        ComponentName guestComponent = handle.getComponentName();
        if (!guestComponent.getPackageName().equals(currentPackage)
                || !TelecomPhoneAccountPolicy.supports(
                        guestComponent.getPackageName(), guestComponent.getClassName())) {
            return handle;
        }
        String stubClass = TelecomPhoneAccountPolicy.stubClassName(slot);
        String encodedId = TelecomPhoneAccountPolicy.encode(
                virtualUserId, guestComponent.getPackageName(), guestComponent.getClassName(),
                handle.getId());
        if (stubClass == null || encodedId == null) {
            return handle;
        }
        return new PhoneAccountHandle(
                new ComponentName(hostPackage, stubClass), encodedId, Process.myUserHandle());
    }

    static PhoneAccount rewriteAccount(PhoneAccount account, String currentPackage,
                                       String hostPackage, int virtualUserId, int slot) {
        if (account == null) {
            return null;
        }
        PhoneAccountHandle rewritten = rewriteHandle(
                account.getAccountHandle(), currentPackage, hostPackage, virtualUserId, slot);
        if (rewritten == account.getAccountHandle()) {
            return account;
        }
        PhoneAccount.Builder builder = PhoneAccount.builder(rewritten, account.getLabel())
                .setAddress(account.getAddress())
                .setSubscriptionAddress(account.getSubscriptionAddress())
                .setCapabilities(account.getCapabilities())
                .setIcon(account.getIcon())
                .setHighlightColor(account.getHighlightColor())
                .setShortDescription(account.getShortDescription())
                .setSupportedUriSchemes(account.getSupportedUriSchemes())
                .setExtras(account.getExtras());
        return builder.build();
    }
}
