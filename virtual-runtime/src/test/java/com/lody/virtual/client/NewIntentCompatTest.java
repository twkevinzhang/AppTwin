package com.lody.virtual.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class NewIntentCompatTest {
    @Test
    public void android10And11DeliverWithActivityToken() {
        for (int sdkInt : new int[]{29, 30}) {
            RecordingDispatcher dispatcher = new RecordingDispatcher();
            Object thread = new Object();
            Object token = new Object();
            List<Object> intents = Collections.<Object>singletonList(new Object());

            assertTrue(NewIntentCompat.dispatch(
                    sdkInt, thread, token, intents, dispatcher, new RecordingWarningSink()));

            assertEquals(0, dispatcher.getActivityClientCalls);
            assertEquals(1, dispatcher.tokenDeliveryCalls);
            assertEquals(0, dispatcher.recordDeliveryCalls);
            assertSame(thread, dispatcher.deliveredThread);
            assertSame(token, dispatcher.deliveredTarget);
            assertSame(intents, dispatcher.deliveredIntents);
        }
    }

    @Test
    public void android12AndLaterResolveTokenToRecordBeforeDelivery() {
        for (int sdkInt : new int[]{31, 36, 37}) {
            RecordingDispatcher dispatcher = new RecordingDispatcher();
            Object thread = new Object();
            Object token = new Object();
            Object record = new Object();
            List<Object> intents = Collections.<Object>singletonList(new Object());
            dispatcher.record = record;

            assertTrue(NewIntentCompat.dispatch(
                    sdkInt, thread, token, intents, dispatcher, new RecordingWarningSink()));

            assertEquals(1, dispatcher.getActivityClientCalls);
            assertEquals(0, dispatcher.tokenDeliveryCalls);
            assertEquals(1, dispatcher.recordDeliveryCalls);
            assertSame(thread, dispatcher.deliveredThread);
            assertSame(record, dispatcher.deliveredTarget);
            assertSame(intents, dispatcher.deliveredIntents);
        }
    }

    @Test
    public void missingAndroid12ActivityRecordSkipsDeliveryAndWarns() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingWarningSink warnings = new RecordingWarningSink();

        assertFalse(NewIntentCompat.dispatch(31, new Object(), new Object(),
                Collections.emptyList(), dispatcher, warnings));

        assertEquals(1, dispatcher.getActivityClientCalls);
        assertEquals(0, dispatcher.tokenDeliveryCalls);
        assertEquals(0, dispatcher.recordDeliveryCalls);
        assertEquals(1, warnings.calls);
        assertTrue(warnings.lastMessage.contains("no ActivityClientRecord"));
    }

    @Test
    public void android12RetryDeliversOnceAfterActivityRecordAppears() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingWarningSink warnings = new RecordingWarningSink();
        Object thread = new Object();
        Object token = new Object();
        List<Object> intents = Collections.<Object>singletonList(new Object());

        assertFalse(NewIntentCompat.dispatch(31, thread, token, intents, dispatcher, warnings));

        Object record = new Object();
        dispatcher.record = record;
        assertTrue(NewIntentCompat.dispatch(31, thread, token, intents, dispatcher, warnings));

        assertEquals(2, dispatcher.getActivityClientCalls);
        assertEquals(0, dispatcher.tokenDeliveryCalls);
        assertEquals(1, dispatcher.recordDeliveryCalls);
        assertSame(record, dispatcher.deliveredTarget);
        assertEquals(1, warnings.calls);
    }

    private static final class RecordingDispatcher implements NewIntentCompat.Dispatcher {
        Object record;
        Object deliveredThread;
        Object deliveredTarget;
        List<?> deliveredIntents;
        int getActivityClientCalls;
        int tokenDeliveryCalls;
        int recordDeliveryCalls;

        @Override
        public Object getActivityClient(Object activityThread, Object token) {
            getActivityClientCalls++;
            return record;
        }

        @Override
        public void handleNewIntentWithToken(Object activityThread, Object token, List<?> intents) {
            tokenDeliveryCalls++;
            recordDelivery(activityThread, token, intents);
        }

        @Override
        public void handleNewIntentWithRecord(Object activityThread, Object record,
                                              List<?> intents) {
            recordDeliveryCalls++;
            recordDelivery(activityThread, record, intents);
        }

        private void recordDelivery(Object activityThread, Object target, List<?> intents) {
            deliveredThread = activityThread;
            deliveredTarget = target;
            deliveredIntents = intents;
        }
    }

    private static final class RecordingWarningSink implements NewIntentCompat.WarningSink {
        int calls;
        String lastMessage;

        @Override
        public void warn(String message) {
            calls++;
            lastMessage = message;
        }
    }
}
