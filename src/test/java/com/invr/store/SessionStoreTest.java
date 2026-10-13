package com.invr.store;

import com.invr.cloud.LoginSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {

    @Test
    void saveLoadRoundTrip(@TempDir Path tempDir) {
        SessionStore store = new SessionStore(tempDir);
        LoginSession session = new LoginSession(
                "V1:123456", "c-uid", "super-secret-pass-token",
                "service-token", "c2VjdXJpdHkta2V5", "cn");

        store.save(session);
        LoginSession loaded = store.load().orElseThrow();

        assertEquals(session, loaded);
    }

    @Test
    void persistedFileIsEncrypted(@TempDir Path tempDir) throws Exception {
        SessionStore store = new SessionStore(tempDir);
        store.save(new LoginSession("u", "c", "plaintext-passToken-marker",
                "st", "c2Vj", "cn"));

        // 密文文件中不得出现敏感明文字段
        String blob = Files.readString(tempDir.resolve("session.json"));
        assertFalse(blob.contains("plaintext-passToken-marker"));
        assertFalse(blob.contains("passToken"));

        // master.key 应为 32 字节
        assertEquals(32, Files.readAllBytes(tempDir.resolve("master.key")).length);
        assertTrue(Files.exists(tempDir.resolve("session.json")));
    }

    @Test
    void loadMissingReturnsEmpty(@TempDir Path tempDir) {
        assertTrue(new SessionStore(tempDir).load().isEmpty());
    }

    @Test
    void clearRemovesSession(@TempDir Path tempDir) {
        SessionStore store = new SessionStore(tempDir);
        store.save(new LoginSession("u", "c", "p", "s", "c2Vj", "cn"));
        store.clear();
        assertTrue(store.load().isEmpty());
    }
}
