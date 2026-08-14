package com.lody.virtual.client.stub;

import android.media.AudioManager;
import android.telecom.Connection;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LineIncomingCallRingtoneTest {
    @Test
    public void startsOnlyForAudibleNormalRingerState() {
        assertTrue(LineIncomingCallRingtone.shouldStart(AudioManager.RINGER_MODE_NORMAL, 1));
        assertFalse(LineIncomingCallRingtone.shouldStart(AudioManager.RINGER_MODE_NORMAL, 0));
        assertFalse(LineIncomingCallRingtone.shouldStart(AudioManager.RINGER_MODE_VIBRATE, 5));
        assertFalse(LineIncomingCallRingtone.shouldStart(AudioManager.RINGER_MODE_SILENT, 5));
    }

    @Test
    public void prefersAvailableLineRingtoneResource() {
        assertTrue(LineIncomingCallRingtone.shouldUseLineRingtone(0x7f140054));
        assertFalse(LineIncomingCallRingtone.shouldUseLineRingtone(0));
    }

    @Test
    public void stopsWhenIncomingConnectionIsAnsweredHeldOrDisconnected() {
        assertTrue(LineIncomingCallRingtone.shouldContinue(Connection.STATE_NEW));
        assertTrue(LineIncomingCallRingtone.shouldContinue(Connection.STATE_INITIALIZING));
        assertTrue(LineIncomingCallRingtone.shouldContinue(Connection.STATE_RINGING));
        assertFalse(LineIncomingCallRingtone.shouldContinue(Connection.STATE_DIALING));
        assertFalse(LineIncomingCallRingtone.shouldContinue(Connection.STATE_ACTIVE));
        assertFalse(LineIncomingCallRingtone.shouldContinue(Connection.STATE_HOLDING));
        assertFalse(LineIncomingCallRingtone.shouldContinue(Connection.STATE_DISCONNECTED));
    }
}
