package android.app;

import static org.junit.Assert.assertEquals;

import android.app.ActivityThread.ActivityClientRecord;
import android.app.servertransaction.PendingTransactionActions;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.IBinder;
import android.util.MergedConfiguration;
import android.view.SurfaceControl;
import android.window.ActivityWindowInfo;
import android.window.SplashScreenView$SplashScreenViewParcelable;
import android.window.WindowContextInfo;

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

        assertVoidMethod(handlerClass, "handleActivityConfigurationChanged",
                ActivityClientRecord.class);
        assertVoidMethod(handlerClass, "handleActivityConfigurationChanged",
                ActivityClientRecord.class, Configuration.class, int.class,
                ActivityWindowInfo.class);
        assertVoidMethod(handlerClass, "handleAttachSplashScreenView",
                ActivityClientRecord.class, SplashScreenView$SplashScreenViewParcelable.class,
                SurfaceControl.class);
        assertVoidMethod(handlerClass, "handleConfigurationChanged",
                Configuration.class, int.class);
        assertVoidMethod(handlerClass, "handleWindowContextInfoChanged",
                IBinder.class, WindowContextInfo.class);
        assertVoidMethod(handlerClass, "handleWindowContextWindowRemoval", IBinder.class);
        Method relaunch = handlerClass.getMethod("prepareRelaunchActivity", IBinder.class,
                java.util.List.class, java.util.List.class, int.class,
                MergedConfiguration.class, boolean.class, ActivityWindowInfo.class, int.class);
        assertEquals(ActivityClientRecord.class, relaunch.getReturnType());
        assertVoidMethod(handlerClass, "reportRefresh", ActivityClientRecord.class);
        assertVoidMethod(handlerClass, "reportRelaunch", ActivityClientRecord.class,
                PendingTransactionActions.class);
        assertVoidMethod(handlerClass, "reportRelaunch", ActivityClientRecord.class);
    }

    private static void assertVoidMethod(Class<?> handlerClass, String name,
                                         Class<?>... parameterTypes) throws Exception {
        assertEquals(void.class, handlerClass.getMethod(name, parameterTypes).getReturnType());
    }
}
