package io.lumenlink.media;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import java.nio.ByteBuffer;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/** Renders an inbound WebRTC video track into a JavaFX ImageView. */
public final class VideoFrameView implements VideoTrackSink, AutoCloseable {
    private final ImageView imageView;
    private VideoTrack track;
    private PixelBuffer<ByteBuffer> pixelBuffer;
    private ByteBuffer pixels;
    private WritableImage image;
    private int width;
    private int height;
    private volatile boolean closed;

    public VideoFrameView(ImageView imageView) {
        this.imageView = imageView;
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
    }

    public void attach(VideoTrack track) {
        detach();
        this.track = track;
        if (track != null) {
            track.addSink(this);
        }
    }

    public void detach() {
        if (track != null) {
            try {
                track.removeSink(this);
            } catch (Exception ignored) {
            }
            track = null;
        }
    }

    @Override
    public void onVideoFrame(VideoFrame frame) {
        if (closed || frame == null || frame.buffer == null) return;
        frame.retain();
        try {
            int frameWidth = frame.buffer.getWidth();
            int frameHeight = frame.buffer.getHeight();
            if (frameWidth <= 0 || frameHeight <= 0) return;
            ensureBuffer(frameWidth, frameHeight);
            pixels.clear();
            VideoBufferConverter.convertFromI420(frame.buffer, pixels, FourCC.BGRA);
            pixels.limit(frameWidth * frameHeight * 4);
            Platform.runLater(() -> {
                if (closed || pixelBuffer == null) return;
                pixelBuffer.updateBuffer(buffer -> null);
            });
        } catch (Exception ignored) {
        } finally {
            frame.release();
        }
    }

    private synchronized void ensureBuffer(int frameWidth, int frameHeight) {
        if (pixels != null && width == frameWidth && height == frameHeight) {
            return;
        }
        width = frameWidth;
        height = frameHeight;
        pixels = ByteBuffer.allocateDirect(frameWidth * frameHeight * 4);
        pixelBuffer = new PixelBuffer<>(frameWidth, frameHeight, pixels, PixelFormat.getByteBgraInstance());
        image = new WritableImage(pixelBuffer);
        Platform.runLater(() -> {
            if (!closed) imageView.setImage(image);
        });
    }

    @Override
    public void close() {
        closed = true;
        detach();
        Platform.runLater(() -> imageView.setImage(null));
    }
}
