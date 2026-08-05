package com.lody.virtual.helper.compat;

import android.content.Intent;
import android.os.IBinder;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
        assertEquals(5, method.getParameterTypes().length);
        assertEquals(long.class, method.getParameterTypes()[4]);
    }

    private static class ConcreteApplicationThread extends ParentApplicationThread {
    }

    private static class ParentApplicationThread {
        @SuppressWarnings("unused")
        private void scheduleBindService(IBinder token, Intent intent, boolean rebind,
                                         int processState, long bindSeq) {
        }
    }
}
