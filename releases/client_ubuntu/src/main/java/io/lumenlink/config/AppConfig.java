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

    /** Temporary hard-coded lab defaults for public test server. */
    public static final String TEST_SERVER_HOST = "8.148.70.189";
    public static final String TEST_NETWORK_CODE = "lumenlink_client_test";

    public static AppConfig defaults() {
        return new AppConfig(
                URI.create(System.getenv().getOrDefault(
                        "LUMENLINK_SIGNAL_URL",
                        "ws://" + TEST_SERVER_HOST + ":8080/ws")),
                TEST_NETWORK_CODE,
                System.getenv().getOrDefault(
                        "LUMENLINK_STUN_URL",
                        "stun:" + TEST_SERVER_HOST + ":3478"),
                true
        );
    }
}
