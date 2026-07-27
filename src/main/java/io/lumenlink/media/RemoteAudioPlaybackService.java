package io.lumenlink.media;

import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/** Controller-side bounded, non-blocking PCM playback for a remote WebRTC audio track. */
public final class RemoteAudioPlaybackService implements AudioTrackSink, AutoCloseable {
    private final ArrayBlockingQueue<AudioChunk> queue = new ArrayBlockingQueue<>(20);
    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lumenlink-remote-audio-playback");
        thread.setDaemon(true);
        return thread;
    });
    private final Object lineLock = new Object();
    private AudioTrack track;
    private SourceDataLine line;
    private int sampleRate;
    private int channels;
    private int bitsPerSample;
    private volatile boolean muted;
    private volatile double volume = 1.0;
    private volatile boolean closed;

    public RemoteAudioPlaybackService() {
        playbackExecutor.execute(this::playbackLoop);
    }

    public synchronized void attach(AudioTrack audioTrack) {
        detach();
        track = audioTrack;
        if (track != null) track.addSink(this);
    }

    public synchronized void detach() {
        if (track != null) {
            try { track.removeSink(this); } catch (Exception ignored) { }
            track = null;
        }
        queue.clear();
        closeLine();
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (muted) {
            queue.clear();
            synchronized (lineLock) {
                if (line != null) line.flush();
            }
        }
    }

    public void setVolume(double volume) {
        this.volume = Math.max(0.0, Math.min(1.0, volume));
    }

    @Override
    public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
        if (closed || muted || data == null || data.length == 0) return;
        AudioChunk chunk = new AudioChunk(Arrays.copyOf(data, data.length), bitsPerSample, sampleRate, channels);
        if (!queue.offer(chunk)) {
            queue.poll();
            queue.offer(chunk);
        }
    }

    private void playbackLoop() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                AudioChunk chunk = queue.poll(250, TimeUnit.MILLISECONDS);
                if (chunk == null || muted) continue;
                byte[] pcm = applyVolume(chunk.data(), chunk.bitsPerSample(), volume);
                synchronized (lineLock) {
                    ensureLine(chunk.bitsPerSample(), chunk.sampleRate(), chunk.channels());
                    line.write(pcm, 0, pcm.length);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                closeLine();
            }
        }
    }

    private void ensureLine(int nextBitsPerSample, int nextSampleRate, int nextChannels) throws Exception {
        if (line != null && bitsPerSample == nextBitsPerSample
                && sampleRate == nextSampleRate && channels == nextChannels) return;
        closeLineLocked();
        bitsPerSample = nextBitsPerSample;
        sampleRate = nextSampleRate;
        channels = nextChannels;
        AudioFormat format = new AudioFormat(sampleRate, bitsPerSample, channels, true, false);
        line = AudioSystem.getSourceDataLine(format);
        int bufferBytes = Math.max(format.getFrameSize() * 480, (int) (format.getFrameRate() * format.getFrameSize() / 10));
        line.open(format, bufferBytes);
        line.start();
    }

    static byte[] applyVolume(byte[] input, int bitsPerSample, double volume) {
        double gain = Math.max(0.0, Math.min(1.0, volume));
        if (gain >= 0.999 || bitsPerSample != 16) return input;
        byte[] output = Arrays.copyOf(input, input.length);
        for (int index = 0; index + 1 < output.length; index += 2) {
            int sample = (short) ((output[index] & 0xFF) | (output[index + 1] << 8));
            int scaled = (int) Math.round(sample * gain);
            output[index] = (byte) (scaled & 0xFF);
            output[index + 1] = (byte) ((scaled >>> 8) & 0xFF);
        }
        return output;
    }

    private void closeLine() {
        synchronized (lineLock) {
            closeLineLocked();
        }
    }

    private void closeLineLocked() {
        if (line == null) return;
        try {
            line.flush();
            line.stop();
            line.close();
        } catch (Exception ignored) { }
        line = null;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        detach();
        playbackExecutor.shutdownNow();
    }

    private record AudioChunk(byte[] data, int bitsPerSample, int sampleRate, int channels) { }
}
