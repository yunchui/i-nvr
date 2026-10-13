package com.invr.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessKeyStoreTest {

    @Test
    void unconfiguredByDefault(@TempDir Path tempDir) {
        AccessKeyStore store = new AccessKeyStore(tempDir);
        assertFalse(store.isConfigured());
        assertFalse(store.verify("whatever".toCharArray()));
    }

    @Test
    void setupThenVerify(@TempDir Path tempDir) {
        AccessKeyStore store = new AccessKeyStore(tempDir);
        store.setup("abc123".toCharArray());
        assertTrue(store.isConfigured());
        assertTrue(store.verify("abc123".toCharArray()));
        assertFalse(store.verify("wrong".toCharArray()));
        assertFalse(store.verify("ABC123".toCharArray()));
        assertFalse(store.verify(new char[0]));
        assertFalse(store.verify(null));
    }

    @Test
    void setupRejectsWeakPassword(@TempDir Path tempDir) {
        AccessKeyStore store = new AccessKeyStore(tempDir);
        assertThrows(IllegalArgumentException.class, () -> store.setup("123".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> store.setup("      ".toCharArray()));
        assertFalse(store.isConfigured());
    }

    @Test
    void cannotSetupTwice(@TempDir Path tempDir) {
        AccessKeyStore store = new AccessKeyStore(tempDir);
        store.setup("abc123".toCharArray());
        assertThrows(IllegalStateException.class, () -> store.setup("xyz789".toCharArray()));
        // 原密码仍有效
        assertTrue(store.verify("abc123".toCharArray()));
    }

    @Test
    void persistedHashHasNoPlaintextAndReloads(@TempDir Path tempDir) throws Exception {
        AccessKeyStore store = new AccessKeyStore(tempDir);
        store.setup("secret-pw-marker".toCharArray());

        Path file = tempDir.resolve("access.json");
        assertTrue(Files.exists(file));
        String json = Files.readString(file);
        assertFalse(json.contains("secret-pw-marker"), "落盘文件不得包含明文密码");
        assertTrue(json.contains("PBKDF2"));

        // 重新加载（模拟重启）后仍可校验
        AccessKeyStore reloaded = new AccessKeyStore(tempDir);
        assertTrue(reloaded.isConfigured());
        assertTrue(reloaded.verify("secret-pw-marker".toCharArray()));
        assertFalse(reloaded.verify("other".toCharArray()));
    }

    @Test
    void samePasswordProducesDifferentSaltHash(@TempDir Path tempDir) throws Exception {
        AccessKeyStore a = new AccessKeyStore(tempDir.resolve("a"));
        AccessKeyStore b = new AccessKeyStore(tempDir.resolve("b"));
        a.setup("samepass1".toCharArray());
        b.setup("samepass1".toCharArray());
        String ja = Files.readString(tempDir.resolve("a").resolve("access.json"));
        String jb = Files.readString(tempDir.resolve("b").resolve("access.json"));
        assertFalse(ja.equals(jb), "随机盐应使相同密码的落盘哈希不同");
    }

    @Test
    void validateRules() {
        assertNotNull(AccessKeyStore.validate(null));
        assertNotNull(AccessKeyStore.validate("12345".toCharArray()));
        assertNotNull(AccessKeyStore.validate("     ".toCharArray()));
        assertNull(AccessKeyStore.validate("123456".toCharArray()));
        assertNull(AccessKeyStore.validate("a-b-c-d".toCharArray()));
    }

    @Test
    void corruptedFileTreatedAsUnconfigured(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("access.json"), "{ not json");
        AccessKeyStore store = assertDoesNotThrow(() -> new AccessKeyStore(tempDir));
        assertFalse(store.isConfigured());
    }

    @Test
    void unlockTokenIsDeterministicAndUrlSafe() {
        char[] pw = "link-pw-123".toCharArray();
        String t1 = AccessKeyStore.buildUnlockToken(pw);
        String t2 = AccessKeyStore.buildUnlockToken(pw);
        assertNotNull(t1);
        assertEquals(t1, t2, "同一密码应派生同一令牌");
        assertNotEquals(t1, AccessKeyStore.buildUnlockToken("link-pw-124".toCharArray()));
        // URL-safe base64，无 padding 与需要转义的字符
        assertFalse(t1.contains("+"));
        assertFalse(t1.contains("/"));
        assertFalse(t1.contains("="));
        assertFalse(t1.contains("?"));
    }

    @Test
    void verifyUnlockTokenAfterSetup(@TempDir Path tempDir) throws Exception {
        AccessKeyStore store = new AccessKeyStore(tempDir);
        char[] pw = "link-pw-123".toCharArray();
        store.setup(pw);

        String token = AccessKeyStore.buildUnlockToken(pw);
        assertTrue(store.verifyUnlockToken(token));
        assertFalse(store.verifyUnlockToken(token + "x"));
        assertFalse(store.verifyUnlockToken("not-a-token"));
        assertFalse(store.verifyUnlockToken(null));
        assertFalse(store.verifyUnlockToken(""));

        // 落盘文件不得包含可直接使用的令牌（只存其二次哈希）
        String json = Files.readString(tempDir.resolve("access.json"));
        assertFalse(json.contains(token), "access.json 不应直接存放解锁令牌");
    }

    @Test
    void legacyRecordWithoutUrlHashIsUpgradedOnRegenerate(@TempDir Path tempDir) throws Exception {
        AccessKeyStore store = new AccessKeyStore(tempDir);
        char[] pw = "legacy-pw-1".toCharArray();
        store.setup(pw);

        // 手工移除 urlHash，模拟旧版本写出的 access.json
        Path file = tempDir.resolve("access.json");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode tree = mapper.readTree(file.toFile());
        ((ObjectNode) tree).remove("urlHash");
        mapper.writeValue(file.toFile(), tree);
        String legacyJson = Files.readString(file);
        assertFalse(legacyJson.contains("urlHash"));
        Files.writeString(file, legacyJson);

        AccessKeyStore legacy = new AccessKeyStore(tempDir);
        assertTrue(legacy.isConfigured());
        String token = AccessKeyStore.buildUnlockToken(pw);
        assertFalse(legacy.verifyUnlockToken(token), "旧记录无 urlHash 时 URL 解锁应不可用");

        // 错误密码不允许重新生成
        assertThrows(IllegalArgumentException.class,
                () -> legacy.regenerateUnlockToken("wrong-pw-9".toCharArray()));

        // 正确密码重新生成：补存 urlHash 并返回可用令牌
        String regenerated = legacy.regenerateUnlockToken(pw);
        assertEquals(token, regenerated);
        assertTrue(legacy.verifyUnlockToken(regenerated), "重新生成后 URL 解锁应可用");
        assertTrue(Files.readString(file).contains("urlHash"), "应已补写 urlHash");

        // 重启加载后令牌仍有效
        AccessKeyStore reloaded = new AccessKeyStore(tempDir);
        assertTrue(reloaded.verifyUnlockToken(token));
    }
}
