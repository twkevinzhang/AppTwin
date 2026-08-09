package org.apptwin.gms.fixture;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class FixtureControlReceiver extends BroadcastReceiver {
    public static final String ACTION_READ =
            "org.apptwin.gms.fixture.action.READ_RESULTS";
    public static final String ACTION_RUN =
            "org.apptwin.gms.fixture.action.RUN_LOCAL_PROBES";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_RUN.equals(intent.getAction())) {
            new LocalProbeRunner(context).runAll();
        } else if (!ACTION_READ.equals(intent.getAction())) {
            setResultCode(Activity.RESULT_CANCELED);
            return;
        }
        setResultExtras(new ProbeStore(context).toResultBundle());
        setResultCode(Activity.RESULT_OK);
    }
}
