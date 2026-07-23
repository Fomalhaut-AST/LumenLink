package io.lumenlink.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Routes WebRTC setup messages only. It never receives screen or control data. */
public final class SignalServer {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Set<WsContext>> rooms = new ConcurrentHashMap<>();
    private final Map<WsContext, String> memberships = new ConcurrentHashMap<>();

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
            if ("JOIN".equals(type)) {
                join(sender, message.path("roomCode").asText());
                return;
            }
            String room = memberships.get(sender);
            if (room != null) broadcast(room, sender, message.toString());
        } catch (Exception error) {
            sender.send(errorMessage("Invalid signaling message"));
        }
    }

    private void join(WsContext peer, String room) {
        if (room == null || room.isBlank()) {
            peer.send(errorMessage("A session code is required"));
            return;
        }
        Set<WsContext> members = rooms.computeIfAbsent(room, ignored -> ConcurrentHashMap.newKeySet());
        if (!members.contains(peer) && members.size() >= 2) {
            peer.send(errorMessage("This session already has two peers"));
            return;
        }
        members.add(peer);
        memberships.put(peer, room);
        if (members.size() == 2) broadcast(room, null, event("PEER_READY", room));
    }

    private void leave(WsContext peer) {
        String room = memberships.remove(peer);
        if (room == null) return;
        Set<WsContext> members = rooms.get(room);
        if (members == null) return;
        members.remove(peer);
        broadcast(room, null, event("PEER_LEFT", room));
        if (members.isEmpty()) rooms.remove(room, members);
    }

    private void broadcast(String room, WsContext sender, String message) {
        Set<WsContext> members = rooms.getOrDefault(room, Set.of());
        members.stream().filter(peer -> peer != sender).forEach(peer -> peer.send(message));
    }

    private String event(String type, String roomCode) {
        return json.valueToTree(Map.of("type", type, "roomCode", roomCode, "payload", Map.of())).toString();
    }

    private String errorMessage(String detail) {
        return json.valueToTree(Map.of("type", "ERROR", "roomCode", "", "payload", Map.of("message", detail))).toString();
    }
}
