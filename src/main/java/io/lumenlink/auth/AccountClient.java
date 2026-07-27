package io.lumenlink.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumenlink.device.DeviceIdentity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/** HTTPS account API paired with the WSS signaling endpoint. */
public final class AccountClient {
    public record AccountDevice(String deviceId, String displayName, String platform,
                                long lastSeenAt, boolean loggedIn, boolean online, boolean current) { }
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI signalingUrl;

    public AccountClient(URI signalingUrl) {
        this.signalingUrl = signalingUrl;
    }

    public CompletableFuture<String> register(String username, String password, DeviceIdentity device) {
        return authenticate("register", username, password, device);
    }

    public CompletableFuture<String> login(String username, String password, DeviceIdentity device) {
        return authenticate("login", username, password, device);
    }

    public CompletableFuture<Void> logout(String token) {
        HttpRequest request = HttpRequest.newBuilder(apiUri("logout"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            ensureSuccess(response);
            return null;
        });
    }

    public CompletableFuture<List<AccountDevice>> devices(String token) {
        HttpRequest request = HttpRequest.newBuilder(apiUri("devices"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            ensureSuccess(response);
            try {
                JsonNode array = json.readTree(response.body()).path("devices");
                List<AccountDevice> result = new ArrayList<>();
                for (JsonNode item : array) {
                    result.add(new AccountDevice(item.path("deviceId").asText(), item.path("displayName").asText(),
                            item.path("platform").asText(), item.path("lastSeenAt").asLong(),
                            item.path("loggedIn").asBoolean(), item.path("online").asBoolean(),
                            item.path("current").asBoolean()));
                }
                return List.copyOf(result);
            } catch (Exception error) {
                throw new IllegalStateException("Invalid device-list response", error);
            }
        });
    }

    public CompletableFuture<Void> changePassword(String token, String currentPassword, String newPassword) {
        return authorizedJson("change-password", "POST", token, Map.of(
                "currentPassword", currentPassword == null ? "" : currentPassword,
                "newPassword", newPassword == null ? "" : newPassword));
    }

    public CompletableFuture<Void> revokeDevice(String token, String deviceId) {
        String encoded = URLEncoder.encode(deviceId, StandardCharsets.UTF_8);
        return authorizedJson("devices/" + encoded, "DELETE", token, null);
    }

    public CompletableFuture<Void> deleteAccount(String token, String password) {
        return authorizedJson("", "DELETE", token, Map.of("password", password == null ? "" : password));
    }

    private CompletableFuture<String> authenticate(String operation, String username, String password, DeviceIdentity device) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", username == null ? "" : username.trim());
            body.put("password", password == null ? "" : password);
            body.put("device", device.toPayload());
            HttpRequest request = HttpRequest.newBuilder(apiUri(operation))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                try {
                    JsonNode result = json.readTree(response.body());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalArgumentException(result.path("error").asText("Account request failed"));
                    }
                    String token = result.path("token").asText("");
                    if (token.isBlank()) throw new IllegalStateException("Server returned no account token");
                    return token;
                } catch (RuntimeException error) {
                    throw error;
                } catch (Exception error) {
                    throw new IllegalStateException("Invalid account response", error);
                }
            });
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private URI apiUri(String operation) {
        try {
            String suffix = operation == null || operation.isBlank() ? "" : "/" + operation;
            return new URI("https", null, signalingUrl.getHost(), signalingUrl.getPort(),
                    "/api/accounts" + suffix, null, null);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid signaling URL", error);
        }
    }

    private CompletableFuture<Void> authorizedJson(
            String operation, String method, String token, Map<String, Object> body) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            HttpRequest request = HttpRequest.newBuilder(apiUri(operation))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .method(method, publisher)
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                ensureSuccess(response);
                return null;
            });
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static void ensureSuccess(HttpResponse<String> response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) return;
        try {
            String error = new ObjectMapper().readTree(response.body()).path("error").asText("Account request failed");
            throw new IllegalArgumentException(error);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Account request failed with HTTP " + response.statusCode());
        }
    }
}
