package io.lumenlink.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void persistsAccountsAndAuthenticatesDeviceTokensAcrossRestart() throws Exception {
        Path database = temporaryDirectory.resolve("accounts.db");
        var device = new ObjectMapper().readTree("""
                {"deviceId":"device-1","displayName":"Test PC","platform":"windows","version":"test"}
                """);
        AccountStore first = new AccountStore(database);
        AccountStore.LoginResult registered = first.register("alice", "correct horse battery staple".toCharArray(), device);

        AccountStore second = new AccountStore(database);
        var authenticated = second.authenticate(registered.token()).orElseThrow();
        assertEquals("alice", authenticated.username());
        assertEquals("device-1", authenticated.deviceId());

        AccountStore.LoginResult loggedIn = second.login("ALICE", "correct horse battery staple".toCharArray(), device);
        assertTrue(second.authenticate(loggedIn.token()).isPresent());
        second.revoke(loggedIn.token());
        assertTrue(second.authenticate(loggedIn.token()).isEmpty());
    }

    @Test
    void rejectsDuplicateUsernameAndWrongPassword() throws Exception {
        var device = new ObjectMapper().readTree("{" +
                "\"deviceId\":\"device-1\",\"displayName\":\"PC\",\"platform\":\"windows\"}");
        AccountStore store = new AccountStore(temporaryDirectory.resolve("accounts.db"));
        store.register("alice", "correct horse battery staple".toCharArray(), device);

        assertThrows(IllegalArgumentException.class,
                () -> store.register("ALICE", "another secure password".toCharArray(), device));
        assertThrows(IllegalArgumentException.class,
                () -> store.login("alice", "the wrong password".toCharArray(), device));
    }

    @Test
    void limitsTheServerToTenRegisteredAccounts() throws Exception {
        var device = new ObjectMapper().readTree("{" +
                "\"deviceId\":\"device-1\",\"displayName\":\"PC\",\"platform\":\"windows\"}");
        AccountStore store = new AccountStore(temporaryDirectory.resolve("accounts.db"));
        for (int index = 0; index < 10; index++) {
            store.register("user_" + index, "correct horse battery staple".toCharArray(), device);
        }

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> store.register("user_10", "correct horse battery staple".toCharArray(), device));
        assertEquals("Account registration limit reached", error.getMessage());
    }

    @Test
    void managesPasswordDevicesTokensAndAccountDeletion() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var firstDevice = json.readTree("{" +
                "\"deviceId\":\"device-1\",\"displayName\":\"First PC\",\"platform\":\"windows\"}");
        var secondDevice = json.readTree("{" +
                "\"deviceId\":\"device-2\",\"displayName\":\"Second PC\",\"platform\":\"windows\"}");
        AccountStore store = new AccountStore(temporaryDirectory.resolve("accounts.db"));
        AccountStore.LoginResult first = store.register(
                "alice", "correct horse battery staple".toCharArray(), firstDevice);
        AccountStore.LoginResult second = store.login(
                "alice", "correct horse battery staple".toCharArray(), secondDevice);
        assertEquals(2, store.devices(first.token()).size());

        store.changePassword(first.token(), "correct horse battery staple".toCharArray(),
                "new correct horse battery staple".toCharArray());
        assertTrue(store.authenticate(first.token()).isPresent());
        assertTrue(store.authenticate(second.token()).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> store.login("alice", "correct horse battery staple".toCharArray(), secondDevice));

        AccountStore.LoginResult replacement = store.login(
                "alice", "new correct horse battery staple".toCharArray(), secondDevice);
        store.revokeDevice(first.token(), "device-2");
        assertTrue(store.authenticate(replacement.token()).isEmpty());
        assertEquals(1, store.devices(first.token()).size());

        assertThrows(IllegalArgumentException.class,
                () -> store.deleteAccount(first.token(), "wrong current password".toCharArray()));
        store.deleteAccount(first.token(), "new correct horse battery staple".toCharArray());
        assertTrue(store.authenticate(first.token()).isEmpty());
    }
}
