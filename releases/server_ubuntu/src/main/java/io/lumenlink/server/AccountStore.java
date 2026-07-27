package io.lumenlink.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

final class AccountStore {
    private static final Duration TOKEN_LIFETIME = Duration.ofDays(90);
    private static final int MAX_ACCOUNTS = 10;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String jdbcUrl;
    private final PasswordHasher passwords = new PasswordHasher();

    record AuthenticatedDevice(String accountId, String username, String deviceId) { }
    record LoginResult(String token, String username) { }
    record DeviceRecord(String deviceId, String displayName, String platform, long lastSeenAt, boolean loggedIn) { }

    AccountStore(Path databaseFile) {
        try {
            Path absolute = databaseFile.toAbsolutePath().normalize();
            if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
            jdbcUrl = "jdbc:sqlite:" + absolute;
            initialize();
        } catch (Exception error) {
            throw new IllegalStateException("Could not initialize account database", error);
        }
    }

    synchronized LoginResult register(String username, char[] password, JsonNode device) throws SQLException {
        String normalized = normalizeUsername(username);
        validatePassword(password);
        String accountId = UUID.randomUUID().toString();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            if (accountCount(connection) >= MAX_ACCOUNTS) {
                connection.rollback();
                throw new IllegalArgumentException("Account registration limit reached");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO accounts(id, username, password_hash, created_at) VALUES(?, ?, ?, ?)")) {
                statement.setString(1, accountId);
                statement.setString(2, normalized);
                statement.setString(3, passwords.hash(password));
                statement.setLong(4, Instant.now().getEpochSecond());
                statement.executeUpdate();
                LoginResult result = issueToken(connection, accountId, normalized, device);
                connection.commit();
                return result;
            } catch (SQLException error) {
                connection.rollback();
                if (error.getMessage() != null && error.getMessage().toLowerCase(Locale.ROOT).contains("unique")) {
                    throw new IllegalArgumentException("Username is already registered");
                }
                throw error;
            }
        }
    }

    synchronized LoginResult login(String username, char[] password, JsonNode device) throws SQLException {
        String normalized = normalizeUsername(username);
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, username, password_hash FROM accounts WHERE username = ? COLLATE NOCASE")) {
            statement.setString(1, normalized);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !passwords.verify(password, result.getString("password_hash"))) {
                    throw new IllegalArgumentException("Invalid username or password");
                }
                return issueToken(connection, result.getString("id"), result.getString("username"), device);
            }
        }
    }

    synchronized Optional<AuthenticatedDevice> authenticate(String token) throws SQLException {
        if (token == null || token.isBlank()) return Optional.empty();
        try (Connection connection = connection()) {
            return authenticate(connection, token);
        }
    }

    synchronized List<DeviceRecord> devices(String token) throws SQLException {
        try (Connection connection = connection()) {
            AuthenticatedDevice authenticated = requireAuthentication(connection, token);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT d.device_id, d.display_name, d.platform, d.last_seen_at,
                      EXISTS(SELECT 1 FROM auth_tokens t
                        WHERE t.account_id=d.account_id AND t.device_id=d.device_id AND t.expires_at>?) AS logged_in
                    FROM devices d WHERE d.account_id=? ORDER BY d.last_seen_at DESC
                    """)) {
                statement.setLong(1, Instant.now().getEpochSecond());
                statement.setString(2, authenticated.accountId());
                List<DeviceRecord> result = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(new DeviceRecord(rows.getString("device_id"), rows.getString("display_name"),
                                rows.getString("platform"), rows.getLong("last_seen_at"), rows.getBoolean("logged_in")));
                    }
                }
                return List.copyOf(result);
            }
        }
    }

    synchronized AuthenticatedDevice changePassword(String token, char[] currentPassword, char[] newPassword) throws SQLException {
        validatePassword(newPassword);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                AuthenticatedDevice authenticated = requireAuthentication(connection, token);
                verifyAccountPassword(connection, authenticated.accountId(), currentPassword);
                try (PreparedStatement update = connection.prepareStatement("UPDATE accounts SET password_hash=? WHERE id=?")) {
                    update.setString(1, passwords.hash(newPassword));
                    update.setString(2, authenticated.accountId());
                    update.executeUpdate();
                }
                try (PreparedStatement revoke = connection.prepareStatement(
                        "DELETE FROM auth_tokens WHERE account_id=? AND token_hash<>?")) {
                    revoke.setString(1, authenticated.accountId());
                    revoke.setString(2, tokenHash(token));
                    revoke.executeUpdate();
                }
                connection.commit();
                return authenticated;
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    synchronized AuthenticatedDevice revokeDevice(String token, String deviceId) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                AuthenticatedDevice authenticated = requireAuthentication(connection, token);
                try (PreparedStatement revoke = connection.prepareStatement(
                        "DELETE FROM auth_tokens WHERE account_id=? AND device_id=?")) {
                    revoke.setString(1, authenticated.accountId());
                    revoke.setString(2, deviceId);
                    revoke.executeUpdate();
                }
                int removed;
                try (PreparedStatement removeDevice = connection.prepareStatement(
                        "DELETE FROM devices WHERE account_id=? AND device_id=?")) {
                    removeDevice.setString(1, authenticated.accountId());
                    removeDevice.setString(2, deviceId);
                    removed = removeDevice.executeUpdate();
                }
                if (removed == 0) throw new IllegalArgumentException("Device not found");
                connection.commit();
                return authenticated;
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    synchronized AuthenticatedDevice deleteAccount(String token, char[] currentPassword) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                AuthenticatedDevice authenticated = requireAuthentication(connection, token);
                verifyAccountPassword(connection, authenticated.accountId(), currentPassword);
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM accounts WHERE id=?")) {
                    statement.setString(1, authenticated.accountId());
                    statement.executeUpdate();
                }
                connection.commit();
                return authenticated;
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private Optional<AuthenticatedDevice> authenticate(Connection connection, String token) throws SQLException {
        if (token == null || token.isBlank()) return Optional.empty();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.account_id, a.username, t.device_id
                FROM auth_tokens t JOIN accounts a ON a.id = t.account_id
                WHERE t.token_hash = ? AND t.expires_at > ?
                """)) {
            statement.setString(1, tokenHash(token));
            statement.setLong(2, Instant.now().getEpochSecond());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new AuthenticatedDevice(
                        result.getString("account_id"), result.getString("username"), result.getString("device_id")));
            }
        }
    }

    private AuthenticatedDevice requireAuthentication(Connection connection, String token) throws SQLException {
        return authenticate(connection, token).orElseThrow(() -> new SecurityException("Unauthorized"));
    }

    private void verifyAccountPassword(Connection connection, String accountId, char[] password) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT password_hash FROM accounts WHERE id=?")) {
            statement.setString(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !passwords.verify(password, result.getString("password_hash"))) {
                    throw new IllegalArgumentException("Invalid current password");
                }
            }
        }
    }

    synchronized void revoke(String token) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM auth_tokens WHERE token_hash = ?")) {
            statement.setString(1, tokenHash(token));
            statement.executeUpdate();
        }
    }

    private LoginResult issueToken(Connection connection, String accountId, String username, JsonNode device) throws SQLException {
        String deviceId = requiredText(device, "deviceId");
        long now = Instant.now().getEpochSecond();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO devices(account_id, device_id, display_name, platform, version, last_seen_at)
                VALUES(?, ?, ?, ?, ?, ?)
                ON CONFLICT(account_id, device_id) DO UPDATE SET
                  display_name=excluded.display_name, platform=excluded.platform,
                  version=excluded.version, last_seen_at=excluded.last_seen_at
                """)) {
            statement.setString(1, accountId);
            statement.setString(2, deviceId);
            statement.setString(3, textOr(device, "displayName", deviceId));
            statement.setString(4, textOr(device, "platform", "unknown"));
            statement.setString(5, textOr(device, "version", ""));
            statement.setLong(6, now);
            statement.executeUpdate();
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO auth_tokens(token_hash, account_id, device_id, created_at, expires_at) VALUES(?, ?, ?, ?, ?)")) {
            statement.setString(1, tokenHash(token));
            statement.setString(2, accountId);
            statement.setString(3, deviceId);
            statement.setLong(4, now);
            statement.setLong(5, now + TOKEN_LIFETIME.toSeconds());
            statement.executeUpdate();
        }
        return new LoginResult(token, username);
    }

    private void initialize() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS accounts(
                      id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                      password_hash TEXT NOT NULL, created_at INTEGER NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS devices(
                      account_id TEXT NOT NULL, device_id TEXT NOT NULL, display_name TEXT NOT NULL,
                      platform TEXT NOT NULL, version TEXT NOT NULL, last_seen_at INTEGER NOT NULL,
                      PRIMARY KEY(account_id, device_id),
                      FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS auth_tokens(
                      token_hash TEXT PRIMARY KEY, account_id TEXT NOT NULL, device_id TEXT NOT NULL,
                      created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
                      FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE)
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS auth_tokens_expiry ON auth_tokens(expires_at)");
            statement.execute("DELETE FROM auth_tokens WHERE expires_at <= " + Instant.now().getEpochSecond());
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private static int accountCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM accounts")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static String normalizeUsername(String username) {
        String value = username == null ? "" : username.trim();
        if (!value.matches("[A-Za-z0-9_.-]{3,64}")) {
            throw new IllegalArgumentException("Username must be 3-64 letters, digits, dots, dashes, or underscores");
        }
        return value;
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < 10 || password.length > 256) {
            throw new IllegalArgumentException("Password must be 10-256 characters");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank() || value.length() > 128) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value.substring(0, Math.min(128, value.length()));
    }

    private static String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
