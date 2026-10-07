package com.lody.virtual.server.am;

import org.junit.Test;
import static org.junit.Assert.*;

public class TerminalServiceCallbackPolicyTest {
    @Test public void ignoresOnlyTerminalOriginalOwnerAfterRetirement() {
        assertTrue(TerminalServiceCallbackPolicy.mayIgnore(true,true,true,true,true,false,true));
    }
    @Test public void liveForeignOrForgedCallbacksKeepNormalAuthorization() {
        assertFalse(TerminalServiceCallbackPolicy.mayIgnore(true,false,true,true,true,false,true));
        assertFalse(TerminalServiceCallbackPolicy.mayIgnore(true,true,false,true,true,false,true));
        assertFalse(TerminalServiceCallbackPolicy.mayIgnore(true,true,true,false,true,false,true));
        assertFalse(TerminalServiceCallbackPolicy.mayIgnore(true,true,true,true,false,false,true));
        assertFalse(TerminalServiceCallbackPolicy.mayIgnore(true,true,true,true,true,true,true));
        assertFalse(TerminalServiceCallbackPolicy.mayIgnore(false,true,true,true,true,false,true));
        assertFalse(TerminalServiceCallbackPolicy.mayIgnore(true,true,true,true,true,false,false));
    }
}
