package com.lody.virtual.client.stub;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Connection;

import com.lody.virtual.helper.utils.VLog;

/** Plays the ringtone that a self-managed cloned LINE call is expected to provide itself. */
final class LineIncomingCallRingtone {
    private static final String TAG = LineIncomingCallRingtone.class.getSimpleName();
    private static final long STATE_POLL_MILLIS = 100L;
    static final String LINE_RINGTONE_RESOURCE = "lineapp_ring_16k";

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stateMonitor = new Runnable() {
        @Override
        public void run() {
            Connection observed = connection;
            if (observed == null) {
                return;
            }
            if (!shouldContinue(observed.getState())) {
                stop();
                return;
            }
            handler.postDelayed(this, STATE_POLL_MILLIS);
        }
    };

    private Connection connection;
    private MediaPlayer lineRingtone;
    private Ringtone systemRingtone;

    LineIncomingCallRingtone(Context context) {
        this.context = context.getApplicationContext();
    }

    void start(Connection incomingConnection, Context guestContext, String guestPackage) {
        stop();
        if (incomingConnection == null) {
            return;
        }
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager == null
                ? AudioManager.RINGER_MODE_SILENT : audioManager.getRingerMode();
        int ringVolume = audioManager == null
                ? 0 : audioManager.getStreamVolume(AudioManager.STREAM_RING);
        if (!shouldStart(ringerMode, ringVolume)) {
            VLog.i(TAG, "Respecting silent or zero-volume ringer state");
            return;
        }
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            MediaPlayer nextLineRingtone = createLineRingtone(
                    guestContext, guestPackage, attributes);
            Ringtone nextSystemRingtone = null;
            if (nextLineRingtone == null) {
                nextSystemRingtone = RingtoneManager.getRingtone(
                        context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
                if (nextSystemRingtone == null) {
                    VLog.w(TAG, "No LINE or system ringtone is available");
                    return;
                }
                nextSystemRingtone.setAudioAttributes(attributes);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    nextSystemRingtone.setLooping(true);
                }
            }
            connection = incomingConnection;
            lineRingtone = nextLineRingtone;
            systemRingtone = nextSystemRingtone;
            if (nextLineRingtone != null) {
                nextLineRingtone.start();
                VLog.i(TAG, "Started cloned LINE incoming-call ringtone using LINE resource");
            } else {
                nextSystemRingtone.play();
                VLog.w(TAG, "Started cloned LINE incoming-call ringtone using system fallback");
            }
            handler.postDelayed(stateMonitor, STATE_POLL_MILLIS);
        } catch (Throwable error) {
            connection = null;
            releaseLineRingtone();
            stopSystemRingtone();
            VLog.e(TAG, "Unable to start cloned LINE incoming-call ringtone", error);
        }
    }

    void stop() {
        handler.removeCallbacks(stateMonitor);
        connection = null;
        boolean hadActiveRingtone = lineRingtone != null || systemRingtone != null;
        releaseLineRingtone();
        stopSystemRingtone();
        if (hadActiveRingtone) {
            VLog.i(TAG, "Stopped cloned LINE incoming-call ringtone");
        }
    }

    private void stopSystemRingtone() {
        Ringtone activeSystemRingtone = systemRingtone;
        systemRingtone = null;
        if (activeSystemRingtone != null) {
            try {
                activeSystemRingtone.stop();
            } catch (Throwable error) {
                VLog.e(TAG, "Unable to stop cloned LINE incoming-call ringtone", error);
            }
        }
    }

    private static MediaPlayer createLineRingtone(
            Context guestContext, String guestPackage, AudioAttributes attributes) {
        if (guestContext == null || guestPackage == null) {
            return null;
        }
        int resourceId = guestContext.getResources().getIdentifier(
                LINE_RINGTONE_RESOURCE, "raw", guestPackage);
        if (!shouldUseLineRingtone(resourceId)) {
            return null;
        }
        MediaPlayer player = MediaPlayer.create(guestContext, resourceId, attributes, 0);
        if (player != null) {
            player.setLooping(true);
        }
        return player;
    }

    private void releaseLineRingtone() {
        MediaPlayer activeLineRingtone = lineRingtone;
        lineRingtone = null;
        if (activeLineRingtone == null) {
            return;
        }
        try {
            activeLineRingtone.stop();
        } catch (Throwable error) {
            VLog.e(TAG, "Unable to stop LINE ringtone resource", error);
        } finally {
            activeLineRingtone.release();
        }
    }

    static boolean shouldStart(int ringerMode, int ringVolume) {
        return ringerMode == AudioManager.RINGER_MODE_NORMAL && ringVolume > 0;
    }

    static boolean shouldUseLineRingtone(int resourceId) {
        return resourceId != 0;
    }

    static boolean shouldContinue(int connectionState) {
        return connectionState == Connection.STATE_NEW
                || connectionState == Connection.STATE_INITIALIZING
                || connectionState == Connection.STATE_RINGING;
    }
}
