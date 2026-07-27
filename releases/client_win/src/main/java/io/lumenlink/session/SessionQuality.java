package io.lumenlink.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Capture and encode quality selected before a control session starts. */
public record SessionQuality(Resolution resolution, int fps, int maxBitrateKbps) {
    public enum Resolution {
        NATIVE("Native (source)", 0, 0),
        // Widescreen 16:9
        UHD_4K("3840×2160 (4K)", 3840, 2160),
        QHD_2K("2560×1440 (2K)", 2560, 1440),
        P1080("1920×1080 (1080p)", 1920, 1080),
        P900("1600×900", 1600, 900),
        P720("1280×720 (720p)", 1280, 720),
        P576("1024×576", 1024, 576),
        P480("854×480", 854, 480),
        // Classic 4:3
        UXGA("1600×1200 (4:3)", 1600, 1200),
        SXGA("1280×1024 (5:4)", 1280, 1024),
        XGA("1024×768 (4:3)", 1024, 768),
        SVGA("800×600 (4:3)", 800, 600),
        VGA("640×480 (4:3)", 640, 480);

        private final String label;
        private final int width;
        private final int height;

        Resolution(String label, int width, int height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }

        public String label() { return label; }
        public int width() { return width; }
        public int height() { return height; }
        public boolean isNative() { return width <= 0 || height <= 0; }

        @Override
        public String toString() { return label; }
    }

    public enum PerformancePreset {
        CUSTOM("Custom", null),
        LOW_POWER("Low power", new SessionQuality(Resolution.P480, 10, 800)),
        BALANCED("Balanced", new SessionQuality(Resolution.P720, 24, 2500)),
        QUALITY("Quality", new SessionQuality(Resolution.P1080, 30, 6000)),
        HIGH_FPS("High frame rate", new SessionQuality(Resolution.P1080, 60, 12000));

        private final String label;
        private final SessionQuality quality;

        PerformancePreset(String label, SessionQuality quality) {
            this.label = label;
            this.quality = quality;
        }

        public SessionQuality quality() { return quality; }

        @Override
        public String toString() { return label; }
    }

    public static final int[] FPS_PRESETS = {5, 10, 15, 24, 30, 60, 90, 120};
    public static final int[] BITRATE_PRESETS_KBPS = {400, 800, 1200, 2500, 4000, 6000, 8000, 12000, 20000, 40000, 60000};

    public SessionQuality {
        Objects.requireNonNull(resolution, "resolution");
        if (fps < 1 || fps > 240) throw new IllegalArgumentException("fps out of range");
        if (maxBitrateKbps < 100) throw new IllegalArgumentException("bitrate too low");
    }

    public static SessionQuality defaults() {
        return PerformancePreset.BALANCED.quality();
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("resolution", resolution.name());
        map.put("fps", fps);
        map.put("maxBitrateKbps", maxBitrateKbps);
        return map;
    }

    public static SessionQuality fromPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return defaults();
        SessionQuality fallback = defaults();
        Resolution resolution = fallback.resolution();
        Object res = payload.get("resolution");
        if (res != null) {
            try {
                resolution = Resolution.valueOf(String.valueOf(res));
            } catch (IllegalArgumentException ignored) {
            }
        }
        int fps = intValue(payload.get("fps"), fallback.fps());
        int bitrate = intValue(payload.get("maxBitrateKbps"), fallback.maxBitrateKbps());
        try {
            return new SessionQuality(resolution, fps, bitrate);
        } catch (IllegalArgumentException error) {
            return defaults();
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    @Override
    public String toString() {
        return resolution.label() + " @ " + fps + " FPS · " + maxBitrateKbps + " kbps";
    }
}
