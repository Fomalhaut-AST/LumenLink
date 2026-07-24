package io.lumenlink.session;

/** Runtime media/connection stats sampled from WebRTC. */
public record SessionStats(
        int videoWidth,
        int videoHeight,
        double fps,
        double videoKbps,
        double audioKbps,
        double roundTripMs,
        long packetsLost,
        String candidatePair
) {
    public static SessionStats empty() {
        return new SessionStats(0, 0, 0, 0, 0, 0, 0, "");
    }

    public String summary() {
        return "Stats: "
                + resolutionText()
                + " | " + decimal(fps) + " FPS"
                + " | video " + decimal(videoKbps) + " kbps"
                + " | audio " + decimal(audioKbps) + " kbps"
                + " | RTT " + decimal(roundTripMs) + " ms"
                + " | lost " + packetsLost
                + (candidatePair == null || candidatePair.isBlank() ? "" : " | " + candidatePair);
    }

    private String resolutionText() {
        return videoWidth > 0 && videoHeight > 0 ? videoWidth + "x" + videoHeight : "--";
    }

    private static String decimal(double value) {
        if (!Double.isFinite(value) || value <= 0) return "--";
        if (value >= 100) return String.format(java.util.Locale.ROOT, "%.0f", value);
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
