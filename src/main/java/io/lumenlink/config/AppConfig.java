package io.lumenlink.config;

import java.net.URI;
import java.util.Objects;

public record AppConfig(URI signalingUrl, String stunUrl, boolean directOnly) {
    public AppConfig {
        Objects.requireNonNull(signalingUrl, "signalingUrl");
        Objects.requireNonNull(stunUrl, "stunUrl");
        if (!"wss".equalsIgnoreCase(signalingUrl.getScheme())) {
            throw new IllegalArgumentException("signalingUrl must use encrypted wss");
        }
    }

    /** Temporary hard-coded lab defaults for public test server. */
    public static final String TEST_SERVER_HOST = "8.148.70.189";
    public static AppConfig defaults() {
        return new AppConfig(
                URI.create(System.getenv().getOrDefault(
                        "LUMENLINK_SIGNAL_URL",
                        "wss://" + TEST_SERVER_HOST + "/ws")),
                System.getenv().getOrDefault(
                        "LUMENLINK_STUN_URL",
                        "stun:" + TEST_SERVER_HOST + ":3478"),
                true
        );
    }
}
