package io.lumenlink.network;

import java.util.Map;
import java.util.Objects;

public record SignalMessage(Type type, String roomCode, Map<String, Object> payload) {
    public enum Type { JOIN, PEER_READY, OFFER, ANSWER, ICE_CANDIDATE, PEER_LEFT, ERROR }

    public SignalMessage {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(roomCode, "roomCode");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
