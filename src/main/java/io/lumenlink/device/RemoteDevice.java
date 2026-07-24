package io.lumenlink.device;

import java.util.Map;
import java.util.Objects;

/** Another client visible through the signaling realm. */
public record RemoteDevice(
        String deviceId,
        String displayName,
        String platform,
        String version
) {
    public RemoteDevice {
        Objects.requireNonNull(deviceId, "deviceId");
        displayName = displayName == null || displayName.isBlank() ? deviceId : displayName;
        platform = platform == null ? "unknown" : platform;
        version = version == null ? "" : version;
    }

    public static RemoteDevice fromMap(Map<?, ?> map) {
        return new RemoteDevice(
                asString(map.get("deviceId"), ""),
                asString(map.get("displayName"), ""),
                asString(map.get("platform"), "unknown"),
                asString(map.get("version"), "")
        );
    }

    private static String asString(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    @Override
    public String toString() {
        return displayName + " (" + platform + ")";
    }
}
