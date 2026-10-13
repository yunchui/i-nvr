package com.invr.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingStateStoreTest {

    @Test
    void enableSurvivesNewInstance(@TempDir Path tempDir) {
        new RecordingStateStore(tempDir).enable("1190193414", 120L);

        // 模拟服务重启：同目录新建实例，期望状态仍在
        Map<String, Long> restored = new RecordingStateStore(tempDir).cameras();
        assertEquals(1, restored.size());
        assertEquals(120L, restored.get("1190193414"));
    }

    @Test
    void enableMultipleAndDisable(@TempDir Path tempDir) {
        RecordingStateStore store = new RecordingStateStore(tempDir);
        store.enable("a", 120L);
        store.enable("b", 15L);
        assertEquals(2, store.cameras().size());

        store.disable("a");
        Map<String, Long> after = new RecordingStateStore(tempDir).cameras();
        assertFalse(after.containsKey("a"));
        assertEquals(15L, after.get("b"));
    }

    @Test
    void disableUnknownIsNoOp(@TempDir Path tempDir) {
        RecordingStateStore store = new RecordingStateStore(tempDir);
        store.enable("a", 120L);
        store.disable("not-there");
        assertEquals(1, new RecordingStateStore(tempDir).cameras().size());
    }

    @Test
    void missingFileReturnsEmpty(@TempDir Path tempDir) {
        assertTrue(new RecordingStateStore(tempDir).cameras().isEmpty());
    }

    @Test
    void corruptFileReturnsEmptyAndDoesNotThrow(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("recordings.json"), "{ this is not valid json ");
        assertTrue(new RecordingStateStore(tempDir).cameras().isEmpty());
    }

    @Test
    void atomicWriteLeavesNoTempFile(@TempDir Path tempDir) {
        new RecordingStateStore(tempDir).enable("a", 120L);
        assertTrue(Files.exists(tempDir.resolve("recordings.json")));
        assertFalse(Files.exists(tempDir.resolve("recordings.json.tmp")));
    }
}
