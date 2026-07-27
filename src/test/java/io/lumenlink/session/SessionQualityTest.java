package io.lumenlink.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionQualityTest {
    @Test
    void roundTripsPayload() {
        SessionQuality quality = new SessionQuality(SessionQuality.Resolution.P720, 60, 20000);
        SessionQuality restored = SessionQuality.fromPayload(quality.toPayload());
        assertEquals(quality, restored);
        assertEquals(Map.of(
                "resolution", "P720",
                "fps", 60,
                "maxBitrateKbps", 20000
        ), quality.toPayload());
    }

    @Test
    void providesLowPowerAndBalancedDefaults() {
        assertEquals(new SessionQuality(SessionQuality.Resolution.P480, 10, 800),
                SessionQuality.PerformancePreset.LOW_POWER.quality());
        assertEquals(new SessionQuality(SessionQuality.Resolution.P720, 24, 2500), SessionQuality.defaults());
        assertEquals(SessionQuality.defaults(), SessionQuality.fromPayload(Map.of("unknown", true)));
    }
}
