package org.apptwin.runtime

import org.apptwin.groups.GroupAppState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustodianActivationPolicyTest {
    @Test
    fun `fresh LINE clone activates Custodian`() {
        assertTrue(
            CustodianActivationPolicy.shouldPrepare(
                LINE,
                hasExistingKeyspace = false,
                appState = GroupAppState.ADDED,
            ),
        )
    }

    @Test
    fun `existing legacy LINE Space remains untouched`() {
        assertFalse(
            CustodianActivationPolicy.shouldPrepare(
                LINE,
                hasExistingKeyspace = false,
                appState = GroupAppState.ENABLED,
            ),
        )
    }

    @Test
    fun `mapped LINE Space is checked again while unrelated apps never activate`() {
        assertTrue(
            CustodianActivationPolicy.shouldPrepare(
                LINE,
                hasExistingKeyspace = true,
                appState = GroupAppState.ENABLED,
            ),
        )
        assertFalse(
            CustodianActivationPolicy.shouldPrepare(
                "com.example.other",
                hasExistingKeyspace = false,
                appState = GroupAppState.ADDED,
            ),
        )
    }

    private companion object {
        const val LINE = "jp.naver.line.android"
    }
}
