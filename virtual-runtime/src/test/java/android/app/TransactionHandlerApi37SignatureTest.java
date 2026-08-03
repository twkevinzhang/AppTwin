package android.app;

import static org.junit.Assert.assertEquals;

import android.app.ActivityThread.ActivityClientRecord;
import android.app.servertransaction.PendingTransactionActions;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

import org.junit.Test;

import java.lang.reflect.Method;

/** Guards descriptors invoked by Android 16 / API 37 TransactionExecutor. */
public class TransactionHandlerApi37SignatureTest {

    @Test
    public void shadowAndProxyExposeAndroid16TransactionDescriptors() throws Exception {
        assertAndroid16Descriptors(ClientTransactionHandler.class);
        assertAndroid16Descriptors(TransactionHandlerProxy.class);
    }

    private static void assertAndroid16Descriptors(Class<?> handlerClass) throws Exception {
        Method packageInfo = handlerClass.getMethod("getPackageInfoNoCheck", ApplicationInfo.class);
        assertEquals(LoadedApk.class, packageInfo.getReturnType());

        Method launch = handlerClass.getMethod("handleLaunchActivity", ActivityClientRecord.class,
                PendingTransactionActions.class, int.class, Intent.class);
        assertEquals(Activity.class, launch.getReturnType());

        Method resume = handlerClass.getMethod("handleResumeActivity", ActivityClientRecord.class,
                boolean.class, boolean.class, boolean.class, String.class);
        assertEquals(void.class, resume.getReturnType());

        Method pause = handlerClass.getMethod("handlePauseActivity", ActivityClientRecord.class,
                boolean.class, boolean.class, boolean.class,
                PendingTransactionActions.class, String.class);
        assertEquals(void.class, pause.getReturnType());

        Method stop = handlerClass.getMethod("handleStopActivity", ActivityClientRecord.class,
                PendingTransactionActions.class, boolean.class, String.class);
        assertEquals(void.class, stop.getReturnType());

        Method destroy = handlerClass.getMethod("handleDestroyActivity", ActivityClientRecord.class,
                boolean.class, boolean.class, String.class);
        assertEquals(void.class, destroy.getReturnType());

        Class<?> sceneTransitionInfo = ActivityOptions$SceneTransitionInfo.class;
        assertEquals("android.app.ActivityOptions$SceneTransitionInfo",
                sceneTransitionInfo.getName());
        Method start = handlerClass.getMethod("handleStartActivity", ActivityClientRecord.class,
                PendingTransactionActions.class, sceneTransitionInfo);
        assertEquals(void.class, start.getReturnType());
        assertEquals(sceneTransitionInfo, start.getParameterTypes()[2]);
    }
}
