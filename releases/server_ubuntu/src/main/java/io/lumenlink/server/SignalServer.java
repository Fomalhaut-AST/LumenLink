package io.lumenlink.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Account-authenticated device registry and WebRTC setup routing only. */
public final class SignalServer {
    private static final int MAX_CONCURRENT_AUTHENTICATIONS = 20;
    private final ObjectMapper json = new ObjectMapper();
    private final AccountStore accounts;
    private final Map<String, Set<WsContext>> rooms = new ConcurrentHashMap<>();
    private final Map<WsContext, Membership> memberships = new ConcurrentHashMap<>();
    private final Map<String, WsContext> devices = new ConcurrentHashMap<>();
    private final Semaphore authenticationSlots = new Semaphore(MAX_CONCURRENT_AUTHENTICATIONS);

    private record Membership(String accountId, String deviceId, ObjectNode device) { }

    private SignalServer(Path databaseFile) {
        accounts = new AccountStore(databaseFile);
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("LUMENLINK_SIGNAL_PORT", "8080"));
        Path database = Path.of(System.getenv().getOrDefault("LUMENLINK_DATABASE_PATH", "data/lumenlink.db"));
        SignalServer server = new SignalServer(database);
        SafeLog.info("server.starting");
        Javalin.create(config -> config.http.maxRequestSize = 64_000L)
                .get("/health", context -> context.json(Map.of("status", "ok")))
                .post("/api/accounts/register", server::registerAccount)
                .post("/api/accounts/login", server::login)
                .post("/api/accounts/logout", server::logout)
                .get("/api/accounts/devices", server::listDevices)
                .post("/api/accounts/change-password", server::changePassword)
                .delete("/api/accounts/devices/{deviceId}", server::revokeDevice)
                .delete("/api/accounts", server::deleteAccount)
                .ws("/ws", ws -> {
                    ws.onMessage(server::route);
                    ws.onClose(server::leave);
                    ws.onError(context -> server.leave(context));
                })
                .start("127.0.0.1", port);
        System.out.printf("LumenLink signaling server listening on http://127.0.0.1:%d behind the WSS proxy%n", port);
    }

    private void registerAccount(Context context) {
        authenticateWithPassword(context, true);
    }

    private void login(Context context) {
        authenticateWithPassword(context, false);
    }

    private void authenticateWithPassword(Context context, boolean registration) {
        if (!authenticationSlots.tryAcquire()) {
            SafeLog.info("account.authentication.rejected_concurrency_limit");
            context.status(429).json(Map.of("error", "Too many authentication requests"));
            return;
        }
        char[] password = null;
        try {
            JsonNode body = json.readTree(context.body());
            String username = body.path("username").asText("");
            password = body.path("password").asText("").toCharArray();
            JsonNode device = body.path("device");
            AccountStore.LoginResult result = registration
                    ? accounts.register(username, password, device)
                    : accounts.login(username, password, device);
            SafeLog.info(registration ? "account.register.succeeded" : "account.login.succeeded");
            context.status(registration ? 201 : 200).json(Map.of(
                    "token", result.token(), "username", result.username(), "expiresInDays", 90));
        } catch (IllegalArgumentException error) {
            SafeLog.warn(registration ? "account.register.rejected" : "account.login.rejected", error);
            context.status(400).json(Map.of("error", error.getMessage()));
        } catch (Exception error) {
            SafeLog.warn("account.authentication.failed", error);
            context.status(500).json(Map.of("error", "Authentication service unavailable"));
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            authenticationSlots.release();
        }
    }

    private void logout(Context context) {
        String token = bearerToken(context.header("Authorization"));
        try {
            Optional<AccountStore.AuthenticatedDevice> authenticated = accounts.authenticate(token);
            if (authenticated.isEmpty()) {
                context.status(401).json(Map.of("error", "Unauthorized"));
                return;
            }
            accounts.revoke(token);
            SafeLog.info("account.logout");
            disconnectDevice(authenticated.get().accountId(), authenticated.get().deviceId(), "Logged out");
            context.status(204);
        } catch (SQLException error) {
            context.status(500).json(Map.of("error", "Authentication service unavailable"));
        }
    }

    private void listDevices(Context context) {
        String token = bearerToken(context.header("Authorization"));
        try {
            AccountStore.AuthenticatedDevice authenticated = accounts.authenticate(token)
                    .orElseThrow(() -> new SecurityException("Unauthorized"));
            List<Map<String, Object>> result = accounts.devices(token).stream().map(device -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("deviceId", device.deviceId());
                item.put("displayName", device.displayName());
                item.put("platform", device.platform());
                item.put("lastSeenAt", device.lastSeenAt());
                item.put("loggedIn", device.loggedIn());
                item.put("online", devices.containsKey(deviceKey(authenticated.accountId(), device.deviceId())));
                item.put("current", device.deviceId().equals(authenticated.deviceId()));
                return item;
            }).toList();
            context.json(Map.of("devices", result));
        } catch (SecurityException error) {
            context.status(401).json(Map.of("error", "Unauthorized"));
        } catch (SQLException error) {
            context.status(500).json(Map.of("error", "Account service unavailable"));
        }
    }

    private void changePassword(Context context) {
        String token = bearerToken(context.header("Authorization"));
        char[] currentPassword = null;
        char[] newPassword = null;
        try {
            JsonNode body = json.readTree(context.body());
            currentPassword = body.path("currentPassword").asText("").toCharArray();
            newPassword = body.path("newPassword").asText("").toCharArray();
            AccountStore.AuthenticatedDevice authenticated = accounts.changePassword(token, currentPassword, newPassword);
            SafeLog.info("account.password.changed");
            disconnectAccountExcept(authenticated.accountId(), authenticated.deviceId(), "Password changed");
            context.status(204);
        } catch (SecurityException error) {
            context.status(401).json(Map.of("error", "Unauthorized"));
        } catch (IllegalArgumentException error) {
            context.status(400).json(Map.of("error", error.getMessage()));
        } catch (Exception error) {
            context.status(500).json(Map.of("error", "Account service unavailable"));
        } finally {
            if (currentPassword != null) Arrays.fill(currentPassword, '\0');
            if (newPassword != null) Arrays.fill(newPassword, '\0');
        }
    }

    private void revokeDevice(Context context) {
        String token = bearerToken(context.header("Authorization"));
        String deviceId = context.pathParam("deviceId");
        try {
            AccountStore.AuthenticatedDevice authenticated = accounts.revokeDevice(token, deviceId);
            SafeLog.info("account.device.revoked");
            disconnectDevice(authenticated.accountId(), deviceId, "Device access revoked");
            context.status(204);
        } catch (SecurityException error) {
            context.status(401).json(Map.of("error", "Unauthorized"));
        } catch (IllegalArgumentException error) {
            context.status(404).json(Map.of("error", error.getMessage()));
        } catch (SQLException error) {
            context.status(500).json(Map.of("error", "Account service unavailable"));
        }
    }

    private void deleteAccount(Context context) {
        String token = bearerToken(context.header("Authorization"));
        char[] password = null;
        try {
            JsonNode body = json.readTree(context.body());
            password = body.path("password").asText("").toCharArray();
            AccountStore.AuthenticatedDevice authenticated = accounts.deleteAccount(token, password);
            SafeLog.info("account.deleted");
            disconnectAccount(authenticated.accountId(), "Account deleted");
            context.status(204);
        } catch (SecurityException error) {
            context.status(401).json(Map.of("error", "Unauthorized"));
        } catch (IllegalArgumentException error) {
            context.status(400).json(Map.of("error", error.getMessage()));
        } catch (Exception error) {
            context.status(500).json(Map.of("error", "Account service unavailable"));
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private void route(WsMessageContext sender) {
        try {
            JsonNode message = json.readTree(sender.message());
            String type = message.path("type").asText();
            switch (type) {
                case "REGISTER" -> registerDevice(sender, message);
                case "SESSION_REQUEST", "SESSION_ACCEPT", "SESSION_REJECT", "SESSION_END",
                     "OFFER", "ANSWER", "ICE_CANDIDATE" -> forward(sender, message);
                default -> sender.send(errorMessage("Unsupported signaling type: " + type));
            }
        } catch (Exception error) {
            SafeLog.warn("signaling.message.rejected", error);
            sender.send(errorMessage("Invalid signaling message"));
        }
    }

    private void registerDevice(WsContext peer, JsonNode message) throws SQLException {
        String token = bearerToken(peer.header("Authorization"));
        Optional<AccountStore.AuthenticatedDevice> authenticated = accounts.authenticate(token);
        if (authenticated.isEmpty()) {
            peer.send(errorMessage("Authentication required"));
            return;
        }
        JsonNode payload = message.path("payload");
        String deviceId = payload.path("deviceId").asText("").trim();
        AccountStore.AuthenticatedDevice login = authenticated.get();
        if (deviceId.isBlank() || !deviceId.equals(login.deviceId())) {
            peer.send(errorMessage("Authenticated device does not match registration"));
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

        String deviceKey = deviceKey(login.accountId(), deviceId);
        WsContext previous = devices.put(deviceKey, peer);
        if (previous != null && previous != peer) leave(previous);

        Membership membership = new Membership(login.accountId(), deviceId, device);
        memberships.put(peer, membership);
        rooms.computeIfAbsent(login.accountId(), ignored -> ConcurrentHashMap.newKeySet()).add(peer);
        broadcastDeviceList(login.accountId());
        SafeLog.info("signaling.device.online");
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
        WsContext target = devices.get(deviceKey(membership.accountId, toDeviceId));
        if (target == null) {
            sender.send(errorMessage("Target device is offline"));
            return;
        }
        Membership targetMembership = memberships.get(target);
        if (targetMembership == null || !membership.accountId.equals(targetMembership.accountId)) {
            sender.send(errorMessage("Target device is not in this account"));
            return;
        }
        ObjectNode outbound = message.deepCopy();
        ObjectNode payload = (ObjectNode) outbound.with("payload");
        payload.put("fromDeviceId", membership.deviceId);
        payload.put("toDeviceId", toDeviceId);
        outbound.put("roomCode", "");
        target.send(outbound.toString());
    }

    private void leave(WsContext peer) {
        Membership membership = memberships.remove(peer);
        if (membership == null) return;
        SafeLog.info("signaling.device.offline");
        devices.remove(deviceKey(membership.accountId, membership.deviceId), peer);
        Set<WsContext> members = rooms.get(membership.accountId);
        if (members == null) return;
        members.remove(peer);
        if (members.isEmpty()) {
            rooms.remove(membership.accountId, members);
            return;
        }
        broadcastDeviceList(membership.accountId);
        ObjectNode offline = json.createObjectNode();
        offline.put("type", "DEVICE_OFFLINE");
        offline.put("roomCode", "");
        offline.putObject("payload").put("deviceId", membership.deviceId);
        members.forEach(member -> member.send(offline.toString()));
    }

    private void broadcastDeviceList(String accountId) {
        Set<WsContext> members = rooms.getOrDefault(accountId, Set.of());
        ArrayNode devicesArray = json.createArrayNode();
        for (WsContext member : members) {
            Membership membership = memberships.get(member);
            if (membership != null) devicesArray.add(membership.device.deepCopy());
        }
        for (WsContext member : members) {
            Membership membership = memberships.get(member);
            if (membership == null) continue;
            ObjectNode message = json.createObjectNode();
            message.put("type", "DEVICE_LIST");
            message.put("roomCode", "");
            ObjectNode payload = message.putObject("payload");
            payload.put("selfDeviceId", membership.deviceId);
            payload.set("devices", devicesArray.deepCopy());
            member.send(message.toString());
        }
    }

    private void disconnectDevice(String accountId, String deviceId, String reason) {
        WsContext peer = devices.get(deviceKey(accountId, deviceId));
        if (peer == null) return;
        leave(peer);
        try {
            peer.closeSession(4001, reason);
        } catch (Exception ignored) {
        }
    }

    private void disconnectAccountExcept(String accountId, String retainedDeviceId, String reason) {
        for (WsContext peer : List.copyOf(rooms.getOrDefault(accountId, Set.of()))) {
            Membership membership = memberships.get(peer);
            if (membership != null && !membership.deviceId.equals(retainedDeviceId)) {
                disconnectDevice(accountId, membership.deviceId, reason);
            }
        }
    }

    private void disconnectAccount(String accountId, String reason) {
        for (WsContext peer : List.copyOf(rooms.getOrDefault(accountId, Set.of()))) {
            Membership membership = memberships.get(peer);
            if (membership != null) disconnectDevice(accountId, membership.deviceId, reason);
        }
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return "";
        return authorization.substring(7).trim();
    }

    private static String deviceKey(String accountId, String deviceId) {
        return accountId + ":" + deviceId;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value.substring(0, Math.min(128, value.length()));
    }

    private String errorMessage(String detail) {
        return json.valueToTree(Map.of("type", "ERROR", "roomCode", "", "payload", Map.of("message", detail))).toString();
    }
}
