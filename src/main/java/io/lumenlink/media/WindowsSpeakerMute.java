package io.lumenlink.media;

import dev.onvoid.webrtc.media.audio.AudioDeviceModule;

/** Temporarily mutes the local default speaker and restores its previous state. */
public final class WindowsSpeakerMute implements AutoCloseable {
    private AudioDeviceModule module;
    private boolean previousMute;
    private boolean applied;

    public void mute() {
        if (applied) return;
        module = new AudioDeviceModule();
        previousMute = module.isSpeakerMuted();
        module.setSpeakerMute(true);
        applied = true;
    }

    @Override
    public void close() {
        if (module != null) {
            try {
                if (applied) {
                    module.setSpeakerMute(previousMute);
                }
                module.dispose();
            } catch (Exception ignored) {
            }
            module = null;
            applied = false;
        }
    }
}
