package io.lumenlink.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ControlPermissionGateTest {
    @Test
    void acceptsEventsOnlyAfterLocalApproval() {
        ControlPermissionGate gate = new ControlPermissionGate();
        RemoteControlEvent event = RemoteControlEvent.mouseMove(0.5, 0.5);

        assertFalse(gate.allows(event));
        assertTrue(gate.grant());
        assertTrue(gate.allows(event));
        gate.revoke();
        assertFalse(gate.allows(event));
    }
}
