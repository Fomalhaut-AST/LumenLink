package io.lumenlink.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;

class RemoteAudioPlaybackServiceTest {
    @Test
    void scalesSignedLittleEndianPcm16() {
        byte[] input = {0x00, 0x40, 0x00, (byte) 0xC0};
        assertArrayEquals(new byte[] {0x00, 0x20, 0x00, (byte) 0xE0},
                RemoteAudioPlaybackService.applyVolume(input, 16, 0.5));
    }

    @Test
    void leavesFullVolumeBufferUnchanged() {
        byte[] input = {1, 2, 3, 4};
        assertSame(input, RemoteAudioPlaybackService.applyVolume(input, 16, 1.0));
    }
}
