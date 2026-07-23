package io.lumenlink.webrtc;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import io.lumenlink.network.DirectPeerPolicy;
import io.lumenlink.network.SignalMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** WebRTC offer/answer and ICE negotiation for a direct-only peer connection. */
public final class DirectPeerSession implements AutoCloseable {
    private final WebRtcEngine engine = new WebRtcEngine();
    private final DirectPeerPolicy.IcePlan icePlan;
    private final Consumer<SignalMessage> signalSender;
    private final Consumer<String> statusListener;
    private final List<RTCIceCandidate> pendingCandidates = new ArrayList<>();
    private RTCPeerConnection peer;
    private boolean remoteDescriptionSet;

    public DirectPeerSession(
            DirectPeerPolicy.IcePlan icePlan,
            Consumer<SignalMessage> signalSender,
            Consumer<String> statusListener) {
        this.icePlan = Objects.requireNonNull(icePlan, "icePlan");
        this.signalSender = Objects.requireNonNull(signalSender, "signalSender");
        this.statusListener = Objects.requireNonNull(statusListener, "statusListener");
    }

    public synchronized void createOffer() {
        RTCPeerConnection connection = ensurePeer();
        connection.createDataChannel("lumenlink-probe", new RTCDataChannelInit());
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

    private void acceptOffer(Map<String, Object> payload) {
        String sdp = requiredString(payload, "sdp");
        RTCPeerConnection connection = ensurePeer();
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
                statusListener.accept("Direct DataChannel opened; peer-to-peer path is ready.");
            }
        });
        return peer;
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
        if (peer != null) {
            peer.close();
            peer = null;
        }
        pendingCandidates.clear();
        engine.close();
    }
}
