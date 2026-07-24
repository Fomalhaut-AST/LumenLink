package io.lumenlink.network;

import java.util.Map;
import java.util.Objects;

public record SignalMessage(Type type, String roomCode, Map<String, Object> payload) {
    public enum Type {
        REGISTER,
        DEVICE_LIST,
        DEVICE_OFFLINE,
        SESSION_REQUEST,
        SESSION_ACCEPT,
        SESSION_REJECT,
        SESSION_END,
        OFFER,
        ANSWER,
        ICE_CANDIDATE,
        ERROR
    }

    public SignalMessage {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(roomCode, "roomCode");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
