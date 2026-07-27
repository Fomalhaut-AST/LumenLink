package io.lumenlink.media;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/** Latest-frame renderer that drops stale frames instead of building latency on slow controllers. */
public final class VideoFrameView implements VideoTrackSink, AutoCloseable {
    private final ImageView imageView;
    private final AtomicReference<VideoFrame> pendingFrame = new AtomicReference<>();
    private final AtomicBoolean uiUpdatePending = new AtomicBoolean();
    private final AtomicBoolean bufferUpdatePending = new AtomicBoolean();
    private final ScheduledExecutorService renderer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "lumenlink-video-renderer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile VideoTrack track;
    private volatile PixelBuffer<ByteBuffer> pixelBuffer;
    private volatile ByteBuffer pixels;
    private volatile int width;
    private volatile int height;
    private volatile boolean closed;

    public VideoFrameView(ImageView imageView, int requestedFps) {
        this.imageView = imageView;
        int displayFps = Math.max(5, Math.min(60, requestedFps));
        imageView.setPreserveRatio(true);
        imageView.setSmooth(displayFps > 15);
        renderer.scheduleAtFixedRate(this::renderLatestFrame, 0,
                Math.max(16, 1000L / displayFps), TimeUnit.MILLISECONDS);
    }

    public synchronized void attach(VideoTrack nextTrack) {
        detach();
        track = nextTrack;
        if (nextTrack != null) nextTrack.addSink(this);
    }

    public synchronized void detach() {
        VideoTrack previousTrack = track;
        track = null;
        if (previousTrack != null) {
            try { previousTrack.removeSink(this); } catch (Exception ignored) { }
        }
        releasePendingFrame();
    }

    @Override
    public void onVideoFrame(VideoFrame frame) {
        if (closed || frame == null || frame.buffer == null) return;
        frame.retain();
        if (closed) {
            frame.release();
            return;
        }
        VideoFrame stale = pendingFrame.getAndSet(frame);
        if (stale != null) stale.release();
        if (closed && pendingFrame.compareAndSet(frame, null)) frame.release();
    }

    private void renderLatestFrame() {
        if (closed || uiUpdatePending.get() || bufferUpdatePending.get()) return;
        VideoFrame frame = pendingFrame.getAndSet(null);
        if (frame == null) return;
        try {
            int frameWidth = frame.buffer.getWidth();
            int frameHeight = frame.buffer.getHeight();
            if (frameWidth <= 0 || frameHeight <= 0) return;
            if (!bufferReady(frameWidth, frameHeight)) {
                requestBuffer(frameWidth, frameHeight);
                return;
            }
            ByteBuffer target = pixels;
            PixelBuffer<ByteBuffer> targetPixelBuffer = pixelBuffer;
            if (target == null || targetPixelBuffer == null) return;
            target.clear();
            VideoBufferConverter.convertFromI420(frame.buffer, target, FourCC.BGRA);
            target.limit(frameWidth * frameHeight * 4);
            uiUpdatePending.set(true);
            Platform.runLater(() -> {
                try {
                    if (!closed && pixelBuffer == targetPixelBuffer) {
                        targetPixelBuffer.updateBuffer(buffer -> null);
                    }
                } finally {
                    uiUpdatePending.set(false);
                }
            });
        } catch (Exception ignored) {
            uiUpdatePending.set(false);
        } finally {
            frame.release();
        }
    }

    private boolean bufferReady(int frameWidth, int frameHeight) {
        return pixels != null && pixelBuffer != null && width == frameWidth && height == frameHeight;
    }

    private void requestBuffer(int frameWidth, int frameHeight) {
        if (!bufferUpdatePending.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            try {
                if (closed) return;
                ByteBuffer nextPixels = ByteBuffer.allocateDirect(frameWidth * frameHeight * 4);
                PixelBuffer<ByteBuffer> nextPixelBuffer = new PixelBuffer<>(
                        frameWidth, frameHeight, nextPixels, PixelFormat.getByteBgraInstance());
                WritableImage nextImage = new WritableImage(nextPixelBuffer);
                pixels = nextPixels;
                pixelBuffer = nextPixelBuffer;
                width = frameWidth;
                height = frameHeight;
                imageView.setImage(nextImage);
            } finally {
                bufferUpdatePending.set(false);
            }
        });
    }

    private void releasePendingFrame() {
        VideoFrame pending = pendingFrame.getAndSet(null);
        if (pending != null) pending.release();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        detach();
        renderer.shutdownNow();
        Platform.runLater(() -> imageView.setImage(null));
    }
}
