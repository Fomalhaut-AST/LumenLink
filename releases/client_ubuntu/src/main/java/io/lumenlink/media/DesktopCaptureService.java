package io.lumenlink.media;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import io.lumenlink.session.SessionQuality;
import java.util.List;

/** Host-side screen capture using webrtc-java desktop source. */
public final class DesktopCaptureService implements AutoCloseable {
    private final VideoDesktopSource source = new VideoDesktopSource();
    private VideoTrack track;
    private boolean started;

    public VideoTrack start(PeerConnectionFactory factory, SessionQuality quality) {
        DesktopSource screen = primaryScreen();
        source.setSourceId(screen.id, false);
        source.setFrameRate(quality.fps());
        if (!quality.resolution().isNative()) {
            source.setMaxFrameSize(quality.resolution().width(), quality.resolution().height());
        }
        track = factory.createVideoTrack("lumenlink-screen", source);
        source.start();
        started = true;
        return track;
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

    @Override
    public void close() {
        if (started) {
            try {
                source.stop();
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
