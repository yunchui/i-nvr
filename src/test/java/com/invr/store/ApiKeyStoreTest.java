package com.invr.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyStoreTest {

    @Test
    void unconfiguredByDefault(@TempDir Path tempDir) {
        ApiKeyStore store = new ApiKeyStore(tempDir);
        assertFalse(store.isConfigured());
        assertFalse(store.verify("anything"));
        assertFalse(store.info().configured());
        assertNull(store.info().mask());
    }

    @Test
    void generateThenVerifyAndReload(@TempDir Path tempDir) {
        ApiKeyStore store = new ApiKeyStore(tempDir);
        String key = store.generate();
        assertTrue(key.startsWith(ApiKeyStore.KEY_PREFIX), "随机密钥应带 invr_ 前缀");
        assertTrue(key.length() >= ApiKeyStore.MIN_CUSTOM_LEN);
        assertTrue(store.isConfigured());
        assertTrue(store.verify(key));
        // 恒定失败路径
        assertFalse(store.verify(key + "x"));
        assertFalse(store.verify(""));
        assertFalse(store.verify(null));

        // 重新构造实例（模拟重启）后摘要仍可校验
        ApiKeyStore reloaded = new ApiKeyStore(tempDir);
        assertTrue(reloaded.isConfigured());
        assertTrue(reloaded.verify(key));
        assertNotNull(reloaded.info().mask());
        assertNotNull(reloaded.info().createdAt());
    }

    @Test
    void regenerateInvalidatesOldKey(@TempDir Path tempDir) {
        ApiKeyStore store = new ApiKeyStore(tempDir);
        String oldKey = store.generate();
        String newKey = store.generate();
        assertFalse(store.verify(oldKey), "重新生成后旧密钥应立即失效");
        assertTrue(store.verify(newKey));
    }

    @Test
    void customKeySaveValidation(@TempDir Path tempDir) {
        ApiKeyStore store = new ApiKeyStore(tempDir);
        assertThrows(IllegalArgumentException.class, () -> store.saveCustom("short"));
        assertThrows(IllegalArgumentException.class,
                () -> store.saveCustom("123456789012345 7890"));
        assertThrows(IllegalArgumentException.class, () -> store.saveCustom(null));
        assertFalse(store.isConfigured(), "校验失败不得落盘");

        String saved = store.saveCustom("  my-secret-api-key-0123456789  ");
        assertEquals("my-secret-api-key-0123456789", saved, "自定义密钥应 trim 后落盘");
        assertTrue(store.verify("my-secret-api-key-0123456789"));
    }

    @Test
    void clearRemovesKeyAndFile(@TempDir Path tempDir) {
        ApiKeyStore store = new ApiKeyStore(tempDir);
        String key = store.generate();
        assertTrue(Files.exists(tempDir.resolve("api-key.json")));
        store.clear();
        assertFalse(store.isConfigured());
        assertFalse(store.verify(key));
        assertFalse(Files.exists(tempDir.resolve("api-key.json")));
    }

    @Test
    void persistedFileContainsNoPlaintextAndMaskHidesIt(@TempDir Path tempDir) throws Exception {
        ApiKeyStore store = new ApiKeyStore(tempDir);
        String key = store.generate();
        Path file = tempDir.resolve("api-key.json");
        String json = Files.readString(file);
        assertFalse(json.contains(key), "落盘文件不得包含明文密钥");

        JsonNode node = new ObjectMapper().readTree(json);
        assertTrue(node.has("keyHash"));
        assertTrue(node.has("mask"));
        assertTrue(node.has("createdAt"));

        String mask = store.info().mask();
        assertFalse(mask.contains(key), "掩码不得暴露完整密钥");
        assertTrue(mask.startsWith(ApiKeyStore.KEY_PREFIX), "掩码保留可辨识前缀");
    }

    @Test
    void corruptedFileTreatedAsUnconfigured(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("api-key.json"), "{ not json");
        ApiKeyStore store = new ApiKeyStore(tempDir);
        assertFalse(store.isConfigured());
    }
}
