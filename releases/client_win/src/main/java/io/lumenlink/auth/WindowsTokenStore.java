package io.lumenlink.auth;

import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Stores the account token encrypted for the current Windows user through DPAPI. */
public final class WindowsTokenStore {
    private final Path file;

    public WindowsTokenStore() {
        this(Path.of(System.getProperty("user.home"), ".lumenlink", "auth-token.dpapi"));
    }

    WindowsTokenStore(Path file) {
        this.file = file;
    }

    public Optional<String> load() {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            byte[] plain = Crypt32Util.cryptUnprotectData(Files.readAllBytes(file));
            String token = new String(plain, StandardCharsets.UTF_8).trim();
            return token.isBlank() ? Optional.empty() : Optional.of(token);
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    public void save(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token is required");
        try {
            Files.createDirectories(file.getParent());
            byte[] encrypted = Crypt32Util.cryptProtectData(token.getBytes(StandardCharsets.UTF_8));
            Files.write(file, encrypted);
        } catch (Exception error) {
            throw new IllegalStateException("Could not save the Windows login token", error);
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (Exception error) {
            throw new IllegalStateException("Could not remove the Windows login token", error);
        }
    }
}
