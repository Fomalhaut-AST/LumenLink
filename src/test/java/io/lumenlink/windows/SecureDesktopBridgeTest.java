package io.lumenlink.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.lumenlink.control.RemoteControlEvent;
import org.junit.jupiter.api.Test;

class SecureDesktopBridgeTest {
    @Test
    void mapsRemoteKeysToWindowsVirtualKeys() {
        assertEquals(0x41, SecureDesktopBridge.windowsVirtualKey("a"));
        assertEquals(0x30, SecureDesktopBridge.windowsVirtualKey("0"));
        assertEquals(0x2E, SecureDesktopBridge.windowsVirtualKey("DELETE"));
        assertEquals(0x5B, SecureDesktopBridge.windowsVirtualKey("WINDOWS"));
        assertEquals(0x7B, SecureDesktopBridge.windowsVirtualKey("F12"));
        assertEquals(0xBD, SecureDesktopBridge.windowsVirtualKey("MINUS"));
        assertEquals(0xDE, SecureDesktopBridge.windowsVirtualKey("QUOTE"));
        assertEquals(0x61, SecureDesktopBridge.windowsVirtualKey("NUMPAD1"));
        assertEquals(0, SecureDesktopBridge.windowsVirtualKey("unknown key"));
    }

    @Test
    void secureAttentionSurvivesDataChannelSerialization() {
        RemoteControlEvent event = RemoteControlEvent.secureAttention();
        assertEquals(event, RemoteControlEvent.fromMap(event.toMap()));
    }
}
