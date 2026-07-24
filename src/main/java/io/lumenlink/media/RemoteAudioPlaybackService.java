package io.lumenlink.media;

import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/** Controller-side PCM playback for a remote WebRTC audio track. */
public final class RemoteAudioPlaybackService implements AudioTrackSink, AutoCloseable {
    private AudioTrack track;
    private SourceDataLine line;
    private int sampleRate;
    private int channels;
    private int bitsPerSample;
    private boolean closed;

    public synchronized void attach(AudioTrack audioTrack) {
        detach();
        track = audioTrack;
        if (track != null) {
            track.addSink(this);
        }
    }

    public synchronized void detach() {
        if (track != null) {
            try {
                track.removeSink(this);
            } catch (Exception ignored) {
            }
            track = null;
        }
        closeLine();
    }

    @Override
    public synchronized void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
        if (closed || data == null || data.length == 0) return;
        try {
            ensureLine(bitsPerSample, sampleRate, channels);
            line.write(data, 0, data.length);
        } catch (Exception ignored) {
            closeLine();
        }
    }

    private void ensureLine(int nextBitsPerSample, int nextSampleRate, int nextChannels) throws Exception {
        if (line != null && bitsPerSample == nextBitsPerSample
                && sampleRate == nextSampleRate && channels == nextChannels) {
            return;
        }
        closeLine();
        bitsPerSample = nextBitsPerSample;
        sampleRate = nextSampleRate;
        channels = nextChannels;
        AudioFormat format = new AudioFormat(sampleRate, bitsPerSample, channels, true, false);
        line = AudioSystem.getSourceDataLine(format);
        line.open(format);
        line.start();
    }

    private void closeLine() {
        if (line != null) {
            try {
                line.drain();
                line.stop();
                line.close();
            } catch (Exception ignored) {
            }
            line = null;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        detach();
    }
}
