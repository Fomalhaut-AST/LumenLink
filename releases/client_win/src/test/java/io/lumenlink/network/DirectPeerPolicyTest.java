package io.lumenlink.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class DirectPeerPolicyTest {
    @Test
    void rejectsTurnRelayCandidates() {
        assertTrue(DirectPeerPolicy.acceptsCandidate("candidate:1 1 udp 2122260223 10.0.0.5 50000 typ host"));
        assertTrue(DirectPeerPolicy.acceptsCandidate("candidate:2 1 udp 1686052607 203.0.113.7 50000 typ srflx"));
        assertFalse(DirectPeerPolicy.acceptsCandidate("candidate:3 1 udp 16777215 198.51.100.4 52000 typ relay"));
    }
}
