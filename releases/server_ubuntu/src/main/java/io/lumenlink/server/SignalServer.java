package io.lumenlink.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Device registry and WebRTC setup routing only.
 * Session media and control never pass through this server.
 */
public final class SignalServer {
    private final ObjectMapper json = new ObjectMapper();
    private final String roomPasswordHash = System.getenv().getOrDefault("LUMENLINK_ROOM_PASSWORD_SHA256", "").trim().toLowerCase();
    private final Map<String, Set<WsContext>> rooms = new ConcurrentHashMap<>();
    private final Map<WsContext, Membership> memberships = new ConcurrentHashMap<>();
    private final Map<String, WsContext> devices = new ConcurrentHashMap<>();

    private record Membership(String room, String deviceId, ObjectNode device) { }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("LUMENLINK_SIGNAL_PORT", "8080"));
        SignalServer server = new SignalServer();
        Javalin.create().ws("/ws", ws -> {
            ws.onMessage(context -> server.route(context));
            ws.onClose(server::leave);
            ws.onError(context -> server.leave(context));
        }).start(port);
        System.out.printf("LumenLink signaling server listening on ws://0.0.0.0:%d/ws%n", port);
    }

    private void route(WsMessageContext sender) {
        try {
            JsonNode message = json.readTree(sender.message());
            String type = message.path("type").asText();
            switch (type) {
                case "REGISTER" -> register(sender, message);
                case "SESSION_REQUEST", "SESSION_ACCEPT", "SESSION_REJECT", "SESSION_END",
                     "OFFER", "ANSWER", "ICE_CANDIDATE" -> forward(sender, message);
                default -> sender.send(errorMessage("Unsupported signaling type: " + type));
            }
        } catch (Exception error) {
            sender.send(errorMessage("Invalid signaling message"));
        }
    }

    private void register(WsContext peer, JsonNode message) {
        String room = message.path("roomCode").asText("").trim();
        JsonNode payload = message.path("payload");
        String deviceId = payload.path("deviceId").asText("").trim();
        String roomPassword = payload.path("roomPassword").asText("");
        if (room.isBlank()) {
            peer.send(errorMessage("A network code is required"));
            return;
        }
        if (deviceId.isBlank()) {
            peer.send(errorMessage("deviceId is required"));
            return;
        }
        if (!passwordAccepted(roomPassword)) {
            peer.send(errorMessage("Invalid room password"));
            return;
        }

        leave(peer);

        ObjectNode device = json.createObjectNode();
        device.put("deviceId", deviceId);
        device.put("displayName", textOr(payload, "displayName", deviceId));
        device.put("platform", textOr(payload, "platform", "unknown"));
        device.put("version", textOr(payload, "version", ""));
        if (payload.has("capabilities") && payload.get("capabilities").isObject()) {
            device.set("capabilities", payload.get("capabilities").deepCopy());
        }

        WsContext previous = devices.put(deviceId, peer);
        if (previous != null && previous != peer) {
            leave(previous);
        }

        Membership membership = new Membership(room, deviceId, device);
        memberships.put(peer, membership);
        rooms.computeIfAbsent(room, ignored -> ConcurrentHashMap.newKeySet()).add(peer);
        broadcastDeviceList(room);
    }

    private void forward(WsContext sender, JsonNode message) {
        Membership membership = memberships.get(sender);
        if (membership == null) {
            sender.send(errorMessage("Register before sending session messages"));
            return;
        }
        String toDeviceId = message.path("payload").path("toDeviceId").asText("").trim();
        if (toDeviceId.isBlank()) {
            sender.send(errorMessage("toDeviceId is required"));
            return;
        }
        WsContext target = devices.get(toDeviceId);
        if (target == null) {
            sender.send(errorMessage("Target device is offline"));
            return;
        }
        Membership targetMembership = memberships.get(target);
        if (targetMembership == null || !membership.room.equals(targetMembership.room)) {
            sender.send(errorMessage("Target device is not in this network"));
            return;
        }
        ObjectNode outbound = message.deepCopy();
        ObjectNode payload = (ObjectNode) outbound.with("payload");
        payload.put("fromDeviceId", membership.deviceId);
        payload.put("toDeviceId", toDeviceId);
        outbound.put("roomCode", membership.room);
        target.send(outbound.toString());
    }

    private void leave(WsContext peer) {
        Membership membership = memberships.remove(peer);
        if (membership == null) return;
        devices.remove(membership.deviceId, peer);
        Set<WsContext> members = rooms.get(membership.room);
        if (members != null) {
            members.remove(peer);
            if (members.isEmpty()) {
                rooms.remove(membership.room, members);
            } else {
                broadcastDeviceList(membership.room);
                ObjectNode offline = json.createObjectNode();
                offline.put("type", "DEVICE_OFFLINE");
                offline.put("roomCode", membership.room);
                ObjectNode payload = offline.putObject("payload");
                payload.put("deviceId", membership.deviceId);
                members.forEach(member -> member.send(offline.toString()));
            }
        }
    }

    private void broadcastDeviceList(String room) {
        Set<WsContext> members = rooms.getOrDefault(room, Set.of());
        ArrayNode devicesArray = json.createArrayNode();
        for (WsContext member : members) {
            Membership membership = memberships.get(member);
            if (membership != null) {
                devicesArray.add(membership.device.deepCopy());
            }
        }
        for (WsContext member : members) {
            Membership membership = memberships.get(member);
            if (membership == null) continue;
            ObjectNode message = json.createObjectNode();
            message.put("type", "DEVICE_LIST");
            message.put("roomCode", room);
            ObjectNode payload = message.putObject("payload");
            payload.put("selfDeviceId", membership.deviceId);
            payload.set("devices", devicesArray.deepCopy());
            member.send(message.toString());
        }
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    private boolean passwordAccepted(String suppliedPassword) {
        if (roomPasswordHash.isBlank()) {
            return true;
        }
        if (suppliedPassword == null || suppliedPassword.isBlank()) {
            return false;
        }
        byte[] expected = roomPasswordHash.getBytes(StandardCharsets.UTF_8);
        byte[] actual = sha256Hex(suppliedPassword).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    private String errorMessage(String detail) {
        return json.valueToTree(Map.of(
                "type", "ERROR",
                "roomCode", "",
                "payload", Map.of("message", detail)
        )).toString();
    }
}
