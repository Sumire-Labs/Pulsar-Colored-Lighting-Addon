package com.sumirelabs.pcla.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResyncGateTest {
    @Test void secondRecoveryWithinCooldownIsDeferredInsteadOfLost() {
        var gate=new ResyncGate(256);
        assertTrue(gate.request(4));assertTrue(gate.ready(4,100));gate.sent(4,100);
        assertTrue(gate.request(4));assertFalse(gate.request(4));assertTrue(gate.pending(4));
        assertFalse(gate.ready(4,101));assertFalse(gate.ready(4,119));assertTrue(gate.ready(4,120));
        gate.sent(4,120);assertFalse(gate.pending(4));
    }
    @Test void slotRetirementDropsPendingRecoveryAndOldCooldown() {
        var gate=new ResyncGate(256);gate.request(4);gate.sent(4,100);gate.request(4);
        assertTrue(gate.request(5));assertTrue(gate.ready(5,101));
        gate.retire(4);assertFalse(gate.pending(4));assertTrue(gate.request(4));assertTrue(gate.ready(4,101));
    }
}
