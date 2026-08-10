package com.lody.virtual.remote;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;

/**
 * @author Lody
 */

public class StubActivityRecord  {
        private static final String EXTRA_INTENT = "_VA_|_intent_";
        private static final String EXTRA_INFO = "_VA_|_info_";
        private static final String EXTRA_CALLER = "_VA_|_caller_";
        private static final String EXTRA_USER_ID = "_VA_|_user_id_";
        private static final String EXTRA_PREPARED_LAUNCH_ID = "_VA_|_prepared_launch_id_";

        public Intent intent;
        public ActivityInfo info;
        public ComponentName caller;
        public int userId;
        public String preparedLaunchId;

        public StubActivityRecord(Intent intent, ActivityInfo info, ComponentName caller, int userId) {
            this(intent, info, caller, userId, null);
        }

        public StubActivityRecord(Intent intent, ActivityInfo info, ComponentName caller, int userId,
                                  String preparedLaunchId) {
            this.intent = intent;
            this.info = info;
            this.caller = caller;
            this.userId = userId;
            this.preparedLaunchId = preparedLaunchId;
        }

        public StubActivityRecord(Intent stub) {
            this.intent = stub.getParcelableExtra(EXTRA_INTENT);
            this.info = stub.getParcelableExtra(EXTRA_INFO);
            this.caller = stub.getParcelableExtra(EXTRA_CALLER);
            this.userId = stub.getIntExtra(EXTRA_USER_ID, 0);
            this.preparedLaunchId = stub.getStringExtra(EXTRA_PREPARED_LAUNCH_ID);
        }

    public void saveToIntent(Intent stub) {
        stub.putExtra(EXTRA_INTENT, intent);
        stub.putExtra(EXTRA_INFO, info);
        stub.putExtra(EXTRA_CALLER, caller);
        stub.putExtra(EXTRA_USER_ID, userId);
        if (preparedLaunchId != null) {
            stub.putExtra(EXTRA_PREPARED_LAUNCH_ID, preparedLaunchId);
        }
    }
}
