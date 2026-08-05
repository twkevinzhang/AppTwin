package com.lody.virtual.server.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class LogicalProcessKeyTest {

    @Test
    public void allThreeFieldsParticipateInIdentity() {
        LogicalProcessKey key = new LogicalProcessKey(
                11062, "jp.naver.line.android", "jp.naver.line.android");

        assertEquals(key, new LogicalProcessKey(
                11062, "jp.naver.line.android", "jp.naver.line.android"));
        assertEquals(key.hashCode(), new LogicalProcessKey(
                11062, "jp.naver.line.android", "jp.naver.line.android").hashCode());
        assertNotEquals(key, new LogicalProcessKey(
                21062, "jp.naver.line.android", "jp.naver.line.android"));
        assertNotEquals(key, new LogicalProcessKey(
                11062, "com.google.android.gms", "jp.naver.line.android"));
        assertNotEquals(key, new LogicalProcessKey(
                11062, "jp.naver.line.android", "jp.naver.line.android:push"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyProcessName() {
        new LogicalProcessKey(11062, "jp.naver.line.android", "");
    }
}
