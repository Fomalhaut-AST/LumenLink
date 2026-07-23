package io.lumenlink.config;

import java.net.URI;
import java.util.Objects;

public record AppConfig(URI signalingUrl, String roomCode, String stunUrl, boolean directOnly) {
    public AppConfig {
        Objects.requireNonNull(signalingUrl, "signalingUrl");
        Objects.requireNonNull(roomCode, "roomCode");
        Objects.requireNonNull(stunUrl, "stunUrl");
        if (roomCode.isBlank()) throw new IllegalArgumentException("roomCode must not be blank");
        if (!"ws".equals(signalingUrl.getScheme()) && !"wss".equals(signalingUrl.getScheme())) {
            throw new IllegalArgumentException("signalingUrl must use ws or wss");
        }
    }

    public static AppConfig defaults() {
        return new AppConfig(
                URI.create(System.getenv().getOrDefault("LUMENLINK_SIGNAL_URL", "ws://127.0.0.1:8080/ws")),
                "new-session",
                System.getenv().getOrDefault("LUMENLINK_STUN_URL", "stun:127.0.0.1:3478"),
                true
        );
    }
}
