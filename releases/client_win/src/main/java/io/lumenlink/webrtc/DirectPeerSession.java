package io.lumenlink.webrtc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;
import io.lumenlink.control.RemoteControlEvent;
import io.lumenlink.media.DesktopCaptureService;
import io.lumenlink.media.RemoteAudioPlaybackService;
import io.lumenlink.media.SystemAudioCaptureService;
import io.lumenlink.media.WindowsSpeakerMute;
import io.lumenlink.network.DirectPeerPolicy;
import io.lumenlink.network.SignalMessage;
import io.lumenlink.session.ControlSession;
import io.lumenlink.session.SessionQuality;
import io.lumenlink.session.SessionStats;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Direct-only WebRTC session with optional screen send and control DataChannel. */
public final class DirectPeerSession implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final WebRtcEngine engine = new WebRtcEngine();
    private final DirectPeerPolicy.IcePlan icePlan;
    private final ControlSession.Role role;
    private final SessionQuality quality;
    private final Consumer<SignalMessage> signalSender;
    private final Consumer<String> statusListener;
    private final Consumer<SessionStats> statsListener;
    private final Consumer<VideoTrack> remoteVideoListener;
    private final Consumer<RemoteControlEvent> controlListener;
    private final List<RTCIceCandidate> pendingCandidates = new ArrayList<>();
    private final SessionStatsSampler statsSampler = new SessionStatsSampler();
    private final ScheduledExecutorService statsExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "lumenlink-stats");
        thread.setDaemon(true);
        return thread;
    });

    private RTCPeerConnection peer;
    private DesktopCaptureService capture;
    private SystemAudioCaptureService audioCapture;
    private RemoteAudioPlaybackService audioPlayback;
    private WindowsSpeakerMute speakerMute;
    private RTCDataChannel controlChannel;
    private boolean remoteDescriptionSet;
    private boolean statsStarted;
    private boolean closed;

    public DirectPeerSession(
            DirectPeerPolicy.IcePlan icePlan,
            ControlSession.Role role,
            SessionQuality quality,
            Consumer<SignalMessage> signalSender,
            Consumer<String> statusListener,
            Consumer<SessionStats> statsListener,
            Consumer<VideoTrack> remoteVideoListener,
            Consumer<RemoteControlEvent> controlListener) {
        this.icePlan = Objects.requireNonNull(icePlan, "icePlan");
        this.role = Objects.requireNonNull(role, "role");
        this.quality = quality == null ? SessionQuality.defaults() : quality;
        this.signalSender = Objects.requireNonNull(signalSender, "signalSender");
        this.statusListener = Objects.requireNonNull(statusListener, "statusListener");
        this.statsListener = statsListener == null ? stats -> { } : statsListener;
        this.remoteVideoListener = remoteVideoListener == null ? track -> { } : remoteVideoListener;
        this.controlListener = controlListener == null ? event -> { } : controlListener;
    }

    public synchronized void createOffer() {
        RTCPeerConnection connection = ensurePeer();
        prepareLocalMedia(connection);
        statusListener.accept("Gathering direct ICE candidates and creating offer...");
        connection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                setLocalAndSend(description, SignalMessage.Type.OFFER);
            }

            @Override
            public void onFailure(String error) {
                statusListener.accept("Could not create WebRTC offer: " + error);
            }
        });
    }

    public synchronized void handleSignal(SignalMessage message) {
        switch (message.type()) {
            case OFFER -> acceptOffer(message.payload());
            case ANSWER -> acceptAnswer(message.payload());
            case ICE_CANDIDATE -> acceptCandidate(message.payload());
            default -> { }
        }
    }

    public void sendControlEvent(RemoteControlEvent event) {
        RTCDataChannel channel = controlChannel;
        if (channel == null || event == null || channel.getState() != RTCDataChannelState.OPEN) return;
        try {
            byte[] bytes = json.writeValueAsBytes(event.toMap());
            channel.send(new RTCDataChannelBuffer(ByteBuffer.wrap(bytes), false));
        } catch (Exception error) {
            statusListener.accept("Could not send control event: " + error.getMessage());
        }
    }

    private void acceptOffer(Map<String, Object> payload) {
        String sdp = requiredString(payload, "sdp");
        RTCPeerConnection connection = ensurePeer();
        prepareLocalMedia(connection);
        connection.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, sdp), new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                onRemoteDescriptionSet();
                connection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                    @Override
                    public void onSuccess(RTCSessionDescription description) {
                        setLocalAndSend(description, SignalMessage.Type.ANSWER);
                    }

                    @Override
                    public void onFailure(String error) {
                        statusListener.accept("Could not create WebRTC answer: " + error);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                statusListener.accept("Could not accept WebRTC offer: " + error);
            }
        });
    }

    private void acceptAnswer(Map<String, Object> payload) {
        String sdp = requiredString(payload, "sdp");
        RTCPeerConnection connection = ensurePeer();
        connection.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, sdp), new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                onRemoteDescriptionSet();
            }

            @Override
            public void onFailure(String error) {
                statusListener.accept("Could not accept WebRTC answer: " + error);
            }
        });
    }

    private synchronized void acceptCandidate(Map<String, Object> payload) {
        String candidateSdp = requiredString(payload, "candidate");
        if (!DirectPeerPolicy.acceptsCandidate(candidateSdp)) {
            statusListener.accept("Rejected TURN relay candidate to preserve direct-only mode.");
            return;
        }
        String mid = String.valueOf(payload.getOrDefault("sdpMid", ""));
        int index = ((Number) payload.getOrDefault("sdpMLineIndex", 0)).intValue();
        RTCIceCandidate candidate = new RTCIceCandidate(mid, index, candidateSdp);
        if (peer == null || !remoteDescriptionSet) {
            pendingCandidates.add(candidate);
        } else {
            peer.addIceCandidate(candidate);
        }
    }

    private void prepareLocalMedia(RTCPeerConnection connection) {
        if (role == ControlSession.Role.HOST && capture == null) {
            capture = new DesktopCaptureService();
            VideoTrack track = capture.start(engine.factory(), quality);
            RTCRtpSender sender = connection.addTrack(track, List.of("lumenlink"));
            applySenderBitrate(sender);
            statusListener.accept("Screen capture started (" + quality + ").");
        }
        if (role == ControlSession.Role.HOST && audioCapture == null) {
            try {
                audioCapture = new SystemAudioCaptureService();
                AudioTrack track = audioCapture.start(engine.factory());
                connection.addTrack(track, List.of("lumenlink"));
                muteLocalSpeaker();
                statusListener.accept("System audio capture started. Host speaker muted.");
            } catch (Exception error) {
                closeAudioCapture();
                statusListener.accept("Could not start system audio capture: " + error.getMessage());
            }
        }
        if (role == ControlSession.Role.CONTROLLER && controlChannel == null) {
            RTCDataChannelInit init = new RTCDataChannelInit();
            init.ordered = true;
            controlChannel = connection.createDataChannel("lumenlink-control", init);
            attachControlChannel(controlChannel);
        }
        if (role == ControlSession.Role.CONTROLLER) {
            RTCRtpTransceiverInit recvOnly = new RTCRtpTransceiverInit();
            recvOnly.direction = RTCRtpTransceiverDirection.RECV_ONLY;
            connection.addTransceiver(null, recvOnly);
        }
    }

    private void applySenderBitrate(RTCRtpSender sender) {
        try {
            var parameters = sender.getParameters();
            if (parameters.encodings == null || parameters.encodings.isEmpty()) {
                parameters.encodings = new ArrayList<>();
                parameters.encodings.add(new RTCRtpEncodingParameters());
            }
            for (RTCRtpEncodingParameters encoding : parameters.encodings) {
                encoding.maxBitrate = quality.maxBitrateKbps() * 1000;
                encoding.maxFramerate = (double) quality.fps();
            }
            sender.setParameters(parameters);
        } catch (Exception error) {
            statusListener.accept("Could not apply bitrate limit: " + error.getMessage());
        }
    }

    private void attachControlChannel(RTCDataChannel channel) {
        channel.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) { }

            @Override
            public void onStateChange() {
                if (channel.getState() == RTCDataChannelState.OPEN) {
                    statusListener.accept("Control channel open.");
                }
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                try {
                    ByteBuffer data = buffer.data;
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = json.readValue(new String(bytes, StandardCharsets.UTF_8), Map.class);
                    controlListener.accept(RemoteControlEvent.fromMap(map));
                } catch (Exception error) {
                    statusListener.accept("Invalid control event: " + error.getMessage());
                }
            }
        });
    }

    private void setLocalAndSend(RTCSessionDescription description, SignalMessage.Type messageType) {
        RTCPeerConnection connection;
        synchronized (this) {
            connection = ensurePeer();
        }
        connection.setLocalDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                signalSender.accept(new SignalMessage(messageType, "", Map.of("sdp", description.sdp)));
                statusListener.accept(messageType == SignalMessage.Type.OFFER
                        ? "Offer sent. Waiting for peer answer..."
                        : "Answer sent. Checking direct UDP connectivity...");
            }

            @Override
            public void onFailure(String error) {
                statusListener.accept("Could not set local WebRTC description: " + error);
            }
        });
    }

    private synchronized RTCPeerConnection ensurePeer() {
        if (peer != null) {
            return peer;
        }
        peer = engine.createDirectPeer(icePlan, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                if (!DirectPeerPolicy.acceptsCandidate(candidate.sdp)) {
                    return;
                }
                signalSender.accept(new SignalMessage(SignalMessage.Type.ICE_CANDIDATE, "", Map.of(
                        "candidate", candidate.sdp,
                        "sdpMid", candidate.sdpMid == null ? "" : candidate.sdpMid,
                        "sdpMLineIndex", candidate.sdpMLineIndex
                )));
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                switch (state) {
                    case CONNECTING -> statusListener.accept("Testing direct UDP candidates...");
                    case CONNECTED -> statusListener.accept("Direct P2P connection established. No relay is in use.");
                    case FAILED -> statusListener.accept("Direct P2P connection failed. This network pair cannot be reached without relay.");
                    case DISCONNECTED -> statusListener.accept("Direct P2P connection was interrupted.");
                    case CLOSED -> statusListener.accept("Direct P2P connection closed.");
                    default -> { }
                }
            }

            @Override
            public void onDataChannel(RTCDataChannel channel) {
                controlChannel = channel;
                attachControlChannel(channel);
                statusListener.accept("Control DataChannel ready.");
            }

            @Override
            public void onTrack(RTCRtpTransceiver transceiver) {
                MediaStreamTrack track = transceiver.getReceiver().getTrack();
                if (track instanceof VideoTrack videoTrack) {
                    remoteVideoListener.accept(videoTrack);
                    statusListener.accept("Remote screen track received.");
                } else if (track instanceof AudioTrack audioTrack) {
                    attachRemoteAudio(audioTrack);
                    statusListener.accept("Remote audio track received.");
                }
            }
        });
        startStatsPolling();
        return peer;
    }

    private synchronized void startStatsPolling() {
        if (statsStarted) return;
        statsStarted = true;
        statsExecutor.scheduleAtFixedRate(() -> {
            RTCPeerConnection connection;
            synchronized (this) {
                if (closed || peer == null) return;
                connection = peer;
            }
            try {
                long now = System.nanoTime();
                connection.getStats(report -> {
                    if (closed) return;
                    statsListener.accept(statsSampler.sample(report, now));
                });
            } catch (Exception ignored) {
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void attachRemoteAudio(AudioTrack audioTrack) {
        if (audioPlayback == null) {
            audioPlayback = new RemoteAudioPlaybackService();
        }
        audioPlayback.attach(audioTrack);
    }

    private void muteLocalSpeaker() {
        try {
            speakerMute = new WindowsSpeakerMute();
            speakerMute.mute();
        } catch (Exception error) {
            closeSpeakerMute();
            statusListener.accept("Could not mute host speaker: " + error.getMessage());
        }
    }

    private void closeAudioCapture() {
        if (audioCapture != null) {
            try {
                audioCapture.close();
            } catch (Exception ignored) {
            }
            audioCapture = null;
        }
    }

    private void closeSpeakerMute() {
        if (speakerMute != null) {
            try {
                speakerMute.close();
            } catch (Exception ignored) {
            }
            speakerMute = null;
        }
    }

    private synchronized void onRemoteDescriptionSet() {
        remoteDescriptionSet = true;
        for (RTCIceCandidate candidate : pendingCandidates) {
            peer.addIceCandidate(candidate);
        }
        pendingCandidates.clear();
    }

    private static String requiredString(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("Missing signaling field: " + field);
        }
        return string;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (controlChannel != null) {
            try {
                controlChannel.unregisterObserver();
                controlChannel.close();
                controlChannel.dispose();
            } catch (Exception ignored) {
            }
            controlChannel = null;
        }
        if (peer != null) {
            peer.close();
            peer = null;
        }
        if (capture != null) {
            capture.close();
            capture = null;
        }
        closeAudioCapture();
        if (audioPlayback != null) {
            try {
                audioPlayback.close();
            } catch (Exception ignored) {
            }
            audioPlayback = null;
        }
        closeSpeakerMute();
        statsExecutor.shutdownNow();
        pendingCandidates.clear();
        engine.close();
    }
}
