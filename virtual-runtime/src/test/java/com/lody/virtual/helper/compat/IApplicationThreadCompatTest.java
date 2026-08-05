package com.lody.virtual.helper.compat;

import android.content.Intent;
import android.os.IBinder;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IApplicationThreadCompatTest {

    @Test
    public void bindSequenceStartsAtAndroid17() {
        assertFalse(IApplicationThreadCompat.usesBindSequence(36));
        assertTrue(IApplicationThreadCompat.usesBindSequence(37));
    }

    @Test
    public void findsAndroid17BindMethodOnConcreteApplicationThread() {
        Method method = IApplicationThreadCompat.findScheduleBindServiceMethod(
                ConcreteApplicationThread.class);

        assertNotNull(method);
        assertEquals(6, method.getParameterTypes().length);
        assertEquals(IBinder.class, method.getParameterTypes()[1]);
        assertEquals(Intent.class, method.getParameterTypes()[2]);
        assertEquals(long.class, method.getParameterTypes()[5]);
    }

    @Test
    public void rejectsObsoleteFiveArgumentAndroid17BindMethod() {
        assertNull(IApplicationThreadCompat.findScheduleBindServiceMethod(
                ObsoleteApplicationThread.class));
    }

    @Test
    public void findsAndroid17UnbindMethodWithBindToken() {
        Method method = IApplicationThreadCompat.findScheduleUnbindServiceMethod(
                ConcreteApplicationThread.class);

        assertNotNull(method);
        assertEquals(3, method.getParameterTypes().length);
        assertEquals(IBinder.class, method.getParameterTypes()[1]);
        assertEquals(Intent.class, method.getParameterTypes()[2]);
    }

    private static class ConcreteApplicationThread extends ParentApplicationThread {
    }

    private static class ParentApplicationThread {
        @SuppressWarnings("unused")
        private void scheduleBindService(IBinder token, IBinder bindToken, Intent intent,
                                         boolean rebind, int processState, long bindSeq) {
        }

        @SuppressWarnings("unused")
        private void scheduleUnbindService(IBinder token, IBinder bindToken, Intent intent) {
        }
    }

    private static class ObsoleteApplicationThread {
        @SuppressWarnings("unused")
        private void scheduleBindService(IBinder token, Intent intent, boolean rebind,
                                         int processState, long bindSeq) {
        }
    }
}
