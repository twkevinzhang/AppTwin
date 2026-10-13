package com.lody.virtual.client.hook.proxies.am;

import org.junit.Test;

import static org.junit.Assert.*;

public class GuestPermissionCheckTest {
    @Test
    public void hostGrantCannotOverrideLegacyOrDeviceAwareGuestDenial() throws Throwable {
        for (String method : new String[]{"checkPermission", "checkPermissionForDevice"}) {
            Object[] args = arguments(method);
            assertEquals(-1, GuestPermissionCheck.dispatch(method, args, true,
                    "fixture", 2, 10042, (permission, pkg, user) -> {
                        assertEquals("android.permission.CAMERA", permission);
                        assertEquals("fixture", pkg);
                        assertEquals(2, user);
                        return -1;
                    }, ignored -> { fail("Controlled denial must never query the host grant"); return 0; }));
            assertEquals(20042, args[2]);
        }
    }

    @Test
    public void asymmetricSpaceDecisionRemainsExact() throws Throwable {
        for (int user : new int[]{2, 3}) {
            assertEquals(user == 2 ? 0 : -1, GuestPermissionCheck.dispatch(
                    "checkPermissionForDevice", arguments("checkPermissionForDevice"), true,
                    "fixture", user, 10042,
                    (permission, pkg, scope) -> scope == 2 && "fixture".equals(pkg) ? 0 : -1,
                    ignored -> { fail("Guest grants are decided in the virtual scope"); return -1; }));
        }
    }

    @Test
    public void hostFallbackRewritesOnlyUidAndPreservesPidPermissionDeviceId() throws Throwable {
        for (String method : new String[]{"checkPermission", "checkPermissionForDevice"}) {
            Object[] args = arguments(method);
            assertEquals(7, GuestPermissionCheck.dispatch(method, args, false,
                    "fixture", 2, 10042,
                    (permission, pkg, user) -> { fail("Uncontrolled query uses host fallback"); return -1; },
                    forwarded -> {
                        assertSame(args, forwarded);
                        assertEquals("android.permission.CAMERA", forwarded[0]);
                        assertEquals(123, forwarded[1]);
                        assertEquals(10042, forwarded[2]);
                        if (forwarded.length == 4) assertEquals(5, forwarded[3]);
                        return 7;
                    }));
        }
    }

    @Test
    public void malformedControlledSignaturesFailClosedBeforeEitherDelegate() throws Throwable {
        Object[][] invalid = {null, {}, {"camera", 1, 2}, {"camera", 1, 2, "device"},
                {"camera", "pid", 2, 0}, {"camera", 1, "uid", 0}, {null, 1, 2, 0}};
        for (Object[] args : invalid) {
            assertEquals(-1, GuestPermissionCheck.dispatch("checkPermissionForDevice", args,
                    true, "fixture", 2, 10042,
                    (permission, pkg, user) -> { fail("Malformed scope must not query virtual state"); return 0; },
                    forwarded -> { fail("Malformed controlled query must not reach host"); return 0; }));
        }
        assertEquals(-1, GuestPermissionCheck.dispatch("unknownMethod", arguments("checkPermission"),
                true, "fixture", 2, 10042,
                (permission, pkg, user) -> { fail(); return 0; }, forwarded -> { fail(); return 0; }));
    }

    @Test
    public void missingGuestIdentityFailsClosed() throws Throwable {
        for (String pkg : new String[]{null, ""}) {
            assertEquals(-1, GuestPermissionCheck.dispatch("checkPermission", arguments("checkPermission"),
                    true, pkg, 2, 10042,
                    (permission, name, user) -> { fail(); return 0; }, forwarded -> { fail(); return 0; }));
        }
        assertEquals(-1, GuestPermissionCheck.dispatch("checkPermission", arguments("checkPermission"),
                true, "fixture", -1, 10042,
                (permission, name, user) -> { fail(); return 0; }, forwarded -> { fail(); return 0; }));
    }

    private static Object[] arguments(String method) {
        return "checkPermissionForDevice".equals(method)
                ? new Object[]{"android.permission.CAMERA", 123, 20042, 5}
                : new Object[]{"android.permission.CAMERA", 123, 20042};
    }
}
