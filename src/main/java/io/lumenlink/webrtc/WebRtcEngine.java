package io.lumenlink.webrtc;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.PortAllocatorConfig;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnection;
import io.lumenlink.network.DirectPeerPolicy;

/** Creates WebRTC peers that gather host and STUN candidates but never TURN relays. */
public final class WebRtcEngine implements AutoCloseable {
    private final PeerConnectionFactory factory = new PeerConnectionFactory();

    public RTCPeerConnection createDirectPeer(DirectPeerPolicy.IcePlan plan, PeerConnectionObserver observer) {
        RTCIceServer stun = new RTCIceServer();
        stun.urls.add(plan.stunUrl());

        RTCConfiguration configuration = new RTCConfiguration();
        configuration.iceServers.add(stun);
        configuration.portAllocatorConfig.setFlag(PortAllocatorConfig.PORTALLOCATOR_DISABLE_RELAY);
        return factory.createPeerConnection(configuration, observer);
    }

    @Override
    public void close() {
        factory.dispose();
    }
}
