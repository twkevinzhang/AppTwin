package com.lody.virtual.client;

import android.os.Build;
import android.os.IBinder;

import com.lody.virtual.helper.utils.VLog;

import java.util.List;

import mirror.android.app.ActivityThread;
import mirror.android.app.ActivityThreadS;

/** Dispatches ActivityThread.handleNewIntent across its Android 10/12 descriptor change. */
final class NewIntentCompat {
    private static final String TAG = "NewIntentCompat";

    interface Dispatcher {
        Object getActivityClient(Object activityThread, Object token);

        void handleNewIntentWithToken(Object activityThread, Object token, List<?> intents);

        void handleNewIntentWithRecord(Object activityThread, Object record, List<?> intents);
    }

    interface WarningSink {
        void warn(String message);
    }

    private NewIntentCompat() {
    }

    static boolean handleNewIntent(Object activityThread, IBinder token, List<?> intents) {
        VLog.d(TAG, "Dispatching new intent via %s descriptor",
                Build.VERSION.SDK_INT >= 31 ? "ActivityClientRecord" : "IBinder");
        return dispatch(Build.VERSION.SDK_INT, activityThread, token, intents,
                new Dispatcher() {
                    @Override
                    public Object getActivityClient(Object thread, Object activityToken) {
                        if (ActivityThreadS.getActivityClient == null) {
                            return null;
                        }
                        return ActivityThreadS.getActivityClient.call(thread, activityToken);
                    }

                    @Override
                    public void handleNewIntentWithToken(Object thread, Object activityToken,
                                                         List<?> pendingIntents) {
                        if (ActivityThread.handleNewIntent == null) {
                            throw new IllegalStateException(
                                    "ActivityThread.handleNewIntent(IBinder, List) is unavailable");
                        }
                        ActivityThread.handleNewIntent.call(thread, activityToken, pendingIntents);
                    }

                    @Override
                    public void handleNewIntentWithRecord(Object thread, Object record,
                                                          List<?> pendingIntents) {
                        if (ActivityThreadS.handleNewIntent == null) {
                            throw new IllegalStateException(
                                    "ActivityThread.handleNewIntent(ActivityClientRecord, List) "
                                            + "is unavailable");
                        }
                        ActivityThreadS.handleNewIntent.call(thread, record, pendingIntents);
                    }
                },
                new WarningSink() {
                    @Override
                    public void warn(String message) {
                        VLog.w(TAG, "%s", message);
                    }
                });
    }

    static boolean dispatch(int sdkInt, Object activityThread, Object token, List<?> intents,
                            Dispatcher dispatcher, WarningSink warningSink) {
        if (sdkInt >= 31) {
            Object record = dispatcher.getActivityClient(activityThread, token);
            if (record == null) {
                warningSink.warn("Unable to deliver new intent: no ActivityClientRecord for token");
                return false;
            }
            dispatcher.handleNewIntentWithRecord(activityThread, record, intents);
            return true;
        }

        dispatcher.handleNewIntentWithToken(activityThread, token, intents);
        return true;
    }
}
