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

/** WSS signaling only. It never carries video frames or remote-control events. */
public final class SignalClient implements AutoCloseable, WebSocket.Listener {
    private final ObjectMapper json = new ObjectMapper();
    private final Consumer<SignalMessage> messageHandler;
    private final StringBuilder fragments = new StringBuilder();
    private volatile WebSocket socket;

    public SignalClient(Consumer<SignalMessage> messageHandler) {
        this.messageHandler = Objects.requireNonNull(messageHandler, "messageHandler");
    }

    public CompletableFuture<WebSocket> connect(URI endpoint) {
        return HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(endpoint, this).thenApply(webSocket -> socket = webSocket);
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
    public void onOpen(WebSocket webSocket) { socket = webSocket; fragments.setLength(0); webSocket.request(1); }

    @Override
    public void close() {
        if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "session ended");
        socket = null;
    }
}
