package io.lumenlink.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent local device identity shared across launches. */
public final class DeviceIdentity {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String deviceId;
    private final String displayName;
    private final String platform;
    private final String version;
    public DeviceIdentity(String deviceId, String displayName, String platform, String version) {
        this.deviceId = deviceId;
        this.displayName = displayName;
        this.platform = platform;
        this.version = version;
    }

    public String deviceId() { return deviceId; }
    public String displayName() { return displayName; }
    public String platform() { return platform; }
    public String version() { return version; }

    public DeviceIdentity withDisplayName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) trimmed = defaultDisplayName();
        return new DeviceIdentity(deviceId, trimmed, platform, version);
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceId", deviceId);
        payload.put("displayName", displayName);
        payload.put("platform", platform);
        payload.put("version", version);
        payload.put("capabilities", Map.of(
                "screen", true,
                "control", true,
                "audio", "windows".equals(platform),
                "files", false
        ));
        return payload;
    }

    public static DeviceIdentity loadOrCreate() {
        Path file = storagePath();
        try {
            if (Files.isRegularFile(file)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = JSON.readValue(Files.readString(file), Map.class);
                String id = stringValue(data.get("deviceId"));
                String name = stringValue(data.get("displayName"));
                if (id != null && !id.isBlank()) {
                    DeviceIdentity loaded = new DeviceIdentity(
                            id,
                            name == null || name.isBlank() ? defaultDisplayName() : name,
                            currentPlatform(),
                            currentVersion()
                    );
                    loaded.save();
                    return loaded;
                }
            }
        } catch (IOException ignored) {
        }
        DeviceIdentity created = new DeviceIdentity(
                UUID.randomUUID().toString(),
                defaultDisplayName(),
                currentPlatform(),
                currentVersion()
        );
        created.save();
        return created;
    }

    public void save() {
        try {
            Path file = storagePath();
            Files.createDirectories(file.getParent());
            Map<String, Object> data = Map.of(
                    "deviceId", deviceId,
                    "displayName", displayName
            );
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data));
        } catch (IOException ignored) {
        }
    }

    private static Path storagePath() {
        return Path.of(System.getProperty("user.home"), ".lumenlink", "device.json");
    }

    private static String defaultDisplayName() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) return host;
        } catch (Exception ignored) {
        }
        return "LumenLink-PC";
    }

    private static String currentPlatform() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("nux") || os.contains("nix")) return "linux";
        if (os.contains("mac")) return "macos";
        return os;
    }

    private static String currentVersion() {
        return "0.1.0-SNAPSHOT";
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
