package com.lody.virtual.client.stub;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
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
    private static final long PLAYBACK_START_DELAY_MILLIS = 250L;
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
            if (shouldStartPlayback(observed.getState())
                    && lineRingtone == null
                    && systemRingtone == null
                    && !playbackAttempted) {
                startPlayback();
            }
            handler.postDelayed(this, STATE_POLL_MILLIS);
        }
    };

    private Connection connection;
    private Context guestContext;
    private String guestPackage;
    private boolean playbackAttempted;
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
        connection = incomingConnection;
        this.guestContext = guestContext;
        this.guestPackage = guestPackage;
        playbackAttempted = false;
        handler.postDelayed(stateMonitor, PLAYBACK_START_DELAY_MILLIS);
        VLog.i(TAG, "Scheduled cloned LINE incoming-call ringtone state="
                + incomingConnection.getState());
    }

    private void startPlayback() {
        playbackAttempted = true;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            MediaPlayer nextLineRingtone = createLineRingtone(
                    context, guestContext, guestPackage, attributes);
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
            lineRingtone = nextLineRingtone;
            systemRingtone = nextSystemRingtone;
            if (nextLineRingtone != null) {
                nextLineRingtone.start();
                VLog.i(TAG, "Started cloned LINE incoming-call ringtone using LINE resource");
            } else {
                nextSystemRingtone.play();
                VLog.w(TAG, "Started cloned LINE incoming-call ringtone using system fallback");
            }
        } catch (Throwable error) {
            releaseLineRingtone();
            stopSystemRingtone();
            VLog.e(TAG, "Unable to start cloned LINE incoming-call ringtone", error);
        }
    }

    void stop() {
        handler.removeCallbacks(stateMonitor);
        connection = null;
        guestContext = null;
        guestPackage = null;
        playbackAttempted = false;
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
            Context hostContext, Context guestContext, String guestPackage,
            AudioAttributes attributes) throws Exception {
        if (hostContext == null || guestContext == null || guestPackage == null) {
            return null;
        }
        int resourceId = guestContext.getResources().getIdentifier(
                LINE_RINGTONE_RESOURCE, "raw", guestPackage);
        if (!shouldUseLineRingtone(resourceId)) {
            return null;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            MediaPlayer legacyPlayer = MediaPlayer.create(
                    guestContext, resourceId, attributes, 0);
            if (legacyPlayer != null) {
                legacyPlayer.setLooping(true);
            }
            return legacyPlayer;
        }
        MediaPlayer player = new MediaPlayer(hostContext);
        try (AssetFileDescriptor resource = guestContext.getResources()
                .openRawResourceFd(resourceId)) {
            if (resource == null) {
                player.release();
                return null;
            }
            player.setAudioAttributes(attributes);
            player.setDataSource(resource);
            player.prepare();
            player.setLooping(true);
            return player;
        } catch (Throwable error) {
            player.release();
            throw error;
        }
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

    static boolean shouldStartPlayback(int connectionState) {
        return shouldContinue(connectionState);
    }
}
