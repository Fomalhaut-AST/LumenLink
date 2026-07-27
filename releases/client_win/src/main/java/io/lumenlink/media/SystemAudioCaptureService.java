package io.lumenlink.media;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import io.lumenlink.windows.SecureDesktopBridge;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Pushes Windows WASAPI loopback PCM from the privileged host into a WebRTC audio track. */
public final class SystemAudioCaptureService implements AutoCloseable {
    private final CustomAudioSource source = new CustomAudioSource();
    private final SecureDesktopBridge bridge;
    private final boolean ownsBridge;
    private final Consumer<Boolean> availabilityListener;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "lumenlink-system-audio");
        thread.setDaemon(true);
        return thread;
    });
    private AudioTrack track;
    private long audioSequence;
    private boolean closed;
    private Boolean lastAvailable;

    public SystemAudioCaptureService() {
        this(new SecureDesktopBridge(), true, available -> { });
    }

    public SystemAudioCaptureService(SecureDesktopBridge bridge) {
        this(bridge, false, available -> { });
    }

    public SystemAudioCaptureService(SecureDesktopBridge bridge, Consumer<Boolean> availabilityListener) {
        this(bridge, false, availabilityListener);
    }

    private SystemAudioCaptureService(SecureDesktopBridge bridge, boolean ownsBridge, Consumer<Boolean> availabilityListener) {
        this.bridge = bridge;
        this.ownsBridge = ownsBridge;
        this.availabilityListener = availabilityListener == null ? available -> { } : availabilityListener;
    }

    public AudioTrack start(PeerConnectionFactory factory) {
        bridge.setAudioRequested(true);
        try {
            track = factory.createAudioTrack("lumenlink-system-audio", source);
            executor.scheduleWithFixedDelay(this::pumpAudio, 0, 4, TimeUnit.MILLISECONDS);
            return track;
        } catch (RuntimeException | Error error) {
            bridge.setAudioRequested(false);
            throw error;
        }
    }

    private void pumpAudio() {
        if (closed) return;
        boolean available = bridge.isAudioAvailable();
        if (lastAvailable == null || lastAvailable != available) {
            lastAvailable = available;
            availabilityListener.accept(available);
        }
        if (!available) return;
        for (int drained = 0; drained < 32; drained++) {
            SecureDesktopBridge.AudioFrame frame = bridge.readAudio(audioSequence);
            if (frame == null) return;
            audioSequence = frame.sequence();
            try {
                source.pushAudio(frame.pcm(), frame.bitsPerSample(), frame.sampleRate(), frame.channels(), frame.frames());
            } catch (Exception ignored) {
                return;
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        executor.shutdownNow();
        if (track != null) {
            try { track.dispose(); } catch (Exception ignored) { }
            track = null;
        }
        try { source.dispose(); } catch (Exception ignored) { }
        bridge.setAudioRequested(false);
        if (ownsBridge) bridge.close();
    }
}
