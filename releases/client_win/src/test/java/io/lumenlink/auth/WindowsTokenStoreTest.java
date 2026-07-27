package io.lumenlink.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class WindowsTokenStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void protectsAndRestoresTokenWithDpapi() throws Exception {
        Path tokenFile = temporaryDirectory.resolve("token.dpapi");
        WindowsTokenStore store = new WindowsTokenStore(tokenFile);
        store.save("secret-device-token");
        assertFalse(new String(Files.readAllBytes(tokenFile), StandardCharsets.UTF_8).contains("secret-device-token"));
        assertEquals("secret-device-token", store.load().orElseThrow());
        store.clear();
        assertTrue(store.load().isEmpty());
    }
}
