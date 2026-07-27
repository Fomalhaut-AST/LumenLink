package io.lumenlink.network;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** WSS signaling only. It never carries video frames or remote-control events. */
public final class SignalClient implements AutoCloseable, WebSocket.Listener {
    private final ObjectMapper json = new ObjectMapper();
    private final Consumer<SignalMessage> messageHandler;
    private final Consumer<Throwable> disconnectHandler;
    private final StringBuilder fragments = new StringBuilder();
    private final AtomicBoolean disconnectNotified = new AtomicBoolean();
    private volatile WebSocket socket;
    private volatile boolean closing;

    public SignalClient(Consumer<SignalMessage> messageHandler, Consumer<Throwable> disconnectHandler) {
        this.messageHandler = Objects.requireNonNull(messageHandler, "messageHandler");
        this.disconnectHandler = Objects.requireNonNull(disconnectHandler, "disconnectHandler");
    }

    public CompletableFuture<WebSocket> connect(URI endpoint, String token) {
        if (token == null || token.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Account token is required"));
        }
        disconnectNotified.set(false);
        closing = false;
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .buildAsync(endpoint, this)
                .thenApply(webSocket -> socket = webSocket);
    }

    public CompletableFuture<WebSocket> send(SignalMessage message) {
        if (socket == null) return CompletableFuture.failedFuture(new IllegalStateException("Signaling socket is not connected"));
        try {
            return socket.sendText(json.writeValueAsString(message), true);
        } catch (JsonProcessingException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        fragments.append(data);
        if (!last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        try {
            messageHandler.accept(json.readValue(fragments.toString(), SignalMessage.class));
        } catch (JsonProcessingException error) {
            return CompletableFuture.failedFuture(error);
        } finally {
            fragments.setLength(0);
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        fragments.setLength(0);
        disconnectNotified.set(false);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        socket = null;
        if (!closing) notifyDisconnected(new IllegalStateException("Signaling connection closed (" + statusCode + ")"));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        socket = null;
        if (!closing) notifyDisconnected(error == null ? new IllegalStateException("Signaling connection failed") : error);
    }

    private void notifyDisconnected(Throwable error) {
        if (disconnectNotified.compareAndSet(false, true)) disconnectHandler.accept(error);
    }

    @Override
    public void close() {
        closing = true;
        if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "session ended");
        socket = null;
    }
}
