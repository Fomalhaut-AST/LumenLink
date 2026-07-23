package io.lumenlink.network;

import io.lumenlink.config.AppConfig;
import java.util.Locale;

/** Rejects relay candidates so media and control data cannot traverse a server. */
public final class DirectPeerPolicy {
    private DirectPeerPolicy() { }

    public static boolean acceptsCandidate(String candidateSdp) {
        return candidateSdp != null && !candidateSdp.isBlank()
                && !candidateSdp.toLowerCase(Locale.ROOT).contains(" typ relay");
    }

    public static IcePlan createIcePlan(AppConfig config) {
        return new IcePlan(config.stunUrl(), config.directOnly());
    }

    public record IcePlan(String stunUrl, boolean relayDisabled) {
        public IcePlan {
            if (stunUrl == null || !stunUrl.startsWith("stun:")) {
                throw new IllegalArgumentException("Only a STUN endpoint is permitted in direct mode");
            }
        }
    }
}
