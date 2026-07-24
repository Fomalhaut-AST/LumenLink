package io.lumenlink.webrtc;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;
import io.lumenlink.session.SessionStats;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Converts generic WebRTC stats reports into a compact UI summary. */
final class SessionStatsSampler {
    private final Map<String, Long> previousBytes = new HashMap<>();
    private final Map<String, Long> previousNanos = new HashMap<>();

    SessionStats sample(RTCStatsReport report, long nowNanos) {
        if (report == null || report.getStats() == null) return SessionStats.empty();
        int width = 0;
        int height = 0;
        double fps = 0;
        double videoKbps = 0;
        double audioKbps = 0;
        double rttMs = 0;
        long packetsLost = 0;
        String pair = "";

        for (RTCStats stat : report.getStats().values()) {
            Map<String, Object> attributes = stat.getAttributes();
            if (attributes == null) continue;
            if (stat.getType() == RTCStatsType.INBOUND_RTP || stat.getType() == RTCStatsType.OUTBOUND_RTP) {
                String kind = mediaKind(stat, attributes);
                double kbps = bitrateKbps(stat.getId(), attributes, nowNanos);
                if ("video".equals(kind)) {
                    videoKbps += kbps;
                    width = firstPositive(width, intField(attributes, "frameWidth", "framesWidth", "width"));
                    height = firstPositive(height, intField(attributes, "frameHeight", "framesHeight", "height"));
                    fps = Math.max(fps, numberField(attributes, "framesPerSecond", "fps"));
                    packetsLost += Math.max(0, longField(attributes, "packetsLost"));
                } else if ("audio".equals(kind)) {
                    audioKbps += kbps;
                    packetsLost += Math.max(0, longField(attributes, "packetsLost"));
                }
            } else if (stat.getType() == RTCStatsType.CANDIDATE_PAIR && isSelected(attributes)) {
                rttMs = Math.max(rttMs, 1000.0 * numberField(attributes,
                        "currentRoundTripTime", "totalRoundTripTime", "roundTripTime"));
                pair = candidatePairText(attributes);
            }
        }
        return new SessionStats(width, height, fps, videoKbps, audioKbps, rttMs, packetsLost, pair);
    }

    private double bitrateKbps(String id, Map<String, Object> attributes, long nowNanos) {
        long bytes = longField(attributes, "bytesReceived", "bytesSent");
        if (bytes <= 0 || id == null || id.isBlank()) return 0;
        Long previous = previousBytes.put(id, bytes);
        Long previousAt = previousNanos.put(id, nowNanos);
        if (previous == null || previousAt == null || nowNanos <= previousAt || bytes < previous) return 0;
        double seconds = (nowNanos - previousAt) / 1_000_000_000.0;
        return seconds <= 0 ? 0 : ((bytes - previous) * 8.0) / seconds / 1000.0;
    }

    private static String mediaKind(RTCStats stat, Map<String, Object> attributes) {
        String kind = textField(attributes, "kind", "mediaType");
        if (!kind.isBlank()) return kind.toLowerCase(Locale.ROOT);
        String id = stat.getId() == null ? "" : stat.getId().toLowerCase(Locale.ROOT);
        if (id.contains("video")) return "video";
        if (id.contains("audio")) return "audio";
        return "";
    }

    private static boolean isSelected(Map<String, Object> attributes) {
        Object selected = attributes.get("selected");
        if (selected instanceof Boolean bool) return bool;
        String state = textField(attributes, "state", "nominated");
        return "true".equalsIgnoreCase(state) || "succeeded".equalsIgnoreCase(state);
    }

    private static String candidatePairText(Map<String, Object> attributes) {
        String local = textField(attributes, "localCandidateType");
        String remote = textField(attributes, "remoteCandidateType");
        if (!local.isBlank() || !remote.isBlank()) return "path " + blankOr(local) + "/" + blankOr(remote);
        return "";
    }

    private static String blankOr(String text) {
        return text == null || text.isBlank() ? "?" : text;
    }

    private static int firstPositive(int current, int next) {
        return current > 0 ? current : Math.max(0, next);
    }

    private static int intField(Map<String, Object> attributes, String... names) {
        return (int) longField(attributes, names);
    }

    private static long longField(Map<String, Object> attributes, String... names) {
        for (String name : names) {
            Object value = attributes.get(name);
            if (value instanceof Number number) return number.longValue();
            if (value != null) {
                try {
                    return Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    private static double numberField(Map<String, Object> attributes, String... names) {
        for (String name : names) {
            Object value = attributes.get(name);
            if (value instanceof Number number) return number.doubleValue();
            if (value != null) {
                try {
                    return Double.parseDouble(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    private static String textField(Map<String, Object> attributes, String... names) {
        for (String name : names) {
            Object value = attributes.get(name);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }
}
