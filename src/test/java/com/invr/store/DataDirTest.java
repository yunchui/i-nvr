package com.invr.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DataDirTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty("invr.data.dir");
        DataDir.resetForTest();
    }

    @Test
    void defaultsToUserDirDotInvr() {
        System.clearProperty("invr.data.dir");
        DataDir.resetForTest();
        Path expected = Paths.get(System.getProperty("user.dir"), DataDir.DIR_NAME);
        assertEquals(expected, DataDir.get());
        // 结果被缓存，重复调用同一实例
        assertSame(DataDir.get(), DataDir.get());
    }

    @Test
    void systemPropertyOverridesDataDir(@TempDir Path tempDir) {
        System.setProperty("invr.data.dir", tempDir.toString());
        DataDir.resetForTest();
        assertEquals(tempDir, DataDir.get());
    }

    @Test
    void blankSystemPropertyFallsBackToDefault() {
        System.setProperty("invr.data.dir", "   ");
        DataDir.resetForTest();
        Path expected = Paths.get(System.getProperty("user.dir"), DataDir.DIR_NAME);
        assertEquals(expected, DataDir.get());
    }
}
