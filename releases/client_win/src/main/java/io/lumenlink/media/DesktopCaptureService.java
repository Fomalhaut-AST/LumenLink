package io.lumenlink.media;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import io.lumenlink.session.SessionQuality;
import io.lumenlink.windows.SecureDesktopBridge;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Host capture that switches between the normal desktop and the Windows lock screen without renegotiating WebRTC. */
public final class DesktopCaptureService implements AutoCloseable {
    private final VideoDesktopSource desktopSource = new VideoDesktopSource();
    private final CustomVideoSource secureSource = new CustomVideoSource();
    private final SecureDesktopBridge secureDesktop;
    private final boolean ownsBridge;
    private final ScheduledExecutorService secureExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "lumenlink-secure-desktop-video");
        thread.setDaemon(true);
        return thread;
    });

    private VideoTrack desktopTrack;
    private VideoTrack secureTrack;
    private VideoTrack activeTrack;
    private Consumer<VideoTrack> trackSwitcher = track -> { };
    private long secureFrameSequence;
    private boolean desktopStarted;
    private boolean closed;

    public DesktopCaptureService() {
        this(new SecureDesktopBridge(), true);
    }

    public DesktopCaptureService(SecureDesktopBridge secureDesktop) {
        this(secureDesktop, false);
    }

    private DesktopCaptureService(SecureDesktopBridge secureDesktop, boolean ownsBridge) {
        this.secureDesktop = secureDesktop;
        this.ownsBridge = ownsBridge;
    }

    public synchronized VideoTrack start(PeerConnectionFactory factory, SessionQuality quality) {
        DesktopSource screen = primaryScreen();
        desktopSource.setSourceId(screen.id, false);
        desktopSource.setFrameRate(quality.fps());
        if (!quality.resolution().isNative()) {
            desktopSource.setMaxFrameSize(quality.resolution().width(), quality.resolution().height());
        }
        desktopTrack = factory.createVideoTrack("lumenlink-screen", desktopSource);
        secureTrack = factory.createVideoTrack("lumenlink-lock-screen", secureSource);
        secureDesktop.configure(quality.resolution().width(), quality.resolution().height(), quality.fps());
        secureDesktop.setVideoRequested(true);
        try {
            desktopSource.start();
            desktopStarted = true;
        } catch (RuntimeException | Error error) {
            secureDesktop.setVideoRequested(false);
            throw error;
        }
        activeTrack = secureDesktop.isLockScreenActive() ? secureTrack : desktopTrack;

        long periodMillis = Math.max(16, 1000L / Math.max(1, Math.min(60, quality.fps())));
        secureExecutor.scheduleAtFixedRate(this::pumpSecureDesktop, 0, periodMillis, TimeUnit.MILLISECONDS);
        return activeTrack;
    }

    public synchronized void setTrackSwitcher(Consumer<VideoTrack> switcher) {
        trackSwitcher = switcher == null ? track -> { } : switcher;
    }

    public static DesktopSource primaryScreen() {
        ScreenCapturer capturer = new ScreenCapturer();
        try {
            List<DesktopSource> screens = capturer.getDesktopSources();
            if (screens == null || screens.isEmpty()) {
                throw new IllegalStateException("No screen capture source is available");
            }
            return screens.getFirst();
        } finally {
            capturer.dispose();
        }
    }

    private void pumpSecureDesktop() {
        if (closed) return;
        boolean locked = secureDesktop.isLockScreenActive();
        switchTrackIfNeeded(locked ? secureTrack : desktopTrack);
        if (!locked) return;
        SecureDesktopBridge.SecureFrame frame = secureDesktop.readFrame(secureFrameSequence);
        if (frame == null) return;
        secureFrameSequence = frame.sequence();
        NativeI420Buffer buffer = null;
        VideoFrame videoFrame = null;
        try {
            buffer = NativeI420Buffer.allocate(frame.width(), frame.height());
            VideoBufferConverter.convertToI420(frame.bgra(), buffer, FourCC.BGRA);
            videoFrame = new VideoFrame(buffer, System.nanoTime());
            secureSource.pushFrame(videoFrame);
        } catch (Exception ignored) {
        } finally {
            if (videoFrame != null) {
                videoFrame.release();
            } else if (buffer != null) {
                buffer.release();
            }
        }
    }

    private synchronized void switchTrackIfNeeded(VideoTrack next) {
        if (closed || next == null || next == activeTrack) return;
        activeTrack = next;
        trackSwitcher.accept(next);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        secureExecutor.shutdownNow();
        if (desktopStarted) {
            try { desktopSource.stop(); } catch (Exception ignored) { }
            desktopStarted = false;
        }
        if (desktopTrack != null) {
            try { desktopTrack.dispose(); } catch (Exception ignored) { }
            desktopTrack = null;
        }
        if (secureTrack != null) {
            try { secureTrack.dispose(); } catch (Exception ignored) { }
            secureTrack = null;
        }
        try { desktopSource.dispose(); } catch (Exception ignored) { }
        try { secureSource.dispose(); } catch (Exception ignored) { }
        secureDesktop.setVideoRequested(false);
        if (ownsBridge) secureDesktop.close();
    }
}
