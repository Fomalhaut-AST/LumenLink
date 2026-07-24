package io.lumenlink.media;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.audio.AudioRecorder;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;

/** Host-side system audio capture pushed into a WebRTC audio track. */
public final class SystemAudioCaptureService implements AutoCloseable {
    private final CustomAudioSource source = new CustomAudioSource();
    private final AudioRecorder recorder = new AudioRecorder();
    private AudioTrack track;
    private boolean started;

    public AudioTrack start(PeerConnectionFactory factory) {
        AudioDevice renderDevice = MediaDevices.getDefaultAudioRenderDevice();
        if (renderDevice != null) {
            recorder.setAudioDevice(renderDevice);
        }
        recorder.setAudioSink((data, bitsPerSample, sampleRate, channels, frames, totalDelayMs, clockDrift) ->
                source.pushAudio(data, bitsPerSample, sampleRate, channels, frames));
        track = factory.createAudioTrack("lumenlink-system-audio", source);
        recorder.start();
        started = true;
        return track;
    }

    @Override
    public void close() {
        if (started) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            started = false;
        }
        if (track != null) {
            try {
                track.dispose();
            } catch (Exception ignored) {
            }
            track = null;
        }
        try {
            source.dispose();
        } catch (Exception ignored) {
        }
    }
}
