package com.invr.store;

import com.invr.model.CameraDevice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link HostResolver} 回退优先级：系统属性 &gt; 云端有效 IP（并记忆）&gt; 历史缓存 &gt; null。
 */
class HostResolverTest {

    private static final String DID = "1190193414";

    @TempDir
    Path dir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("invr.host." + DID);
    }

    private CameraDevice cam(String ip) {
        return new CameraDevice(DID, "客厅", "xiaomi.camera.c302", "aa:bb:cc:dd:ee:ff", ip, true);
    }

    @Test
    void validIp_usesCloudAndPersistsCache() {
        HostResolver r = new HostResolver(dir);
        String host = r.resolve(cam("10.10.10.14"));
        assertEquals("10.10.10.14", host);
        // 已记忆到 hosts.json
        HostResolver reloaded = new HostResolver(dir);
        assertEquals("10.10.10.14", reloaded.resolve(cam("10.10.10.14")));
        assertTrue(Files.exists(dir.resolve("hosts.json")));
    }

    @Test
    void cloudZero_fallsBackToCachedIp() {
        // 先在云端正常时记住 10.10.10.14
        new HostResolver(dir).resolve(cam("10.10.10.14"));
        // 之后云端返回 0.0.0.0：回退历史缓存
        HostResolver r2 = new HostResolver(dir);
        assertEquals("10.10.10.14", r2.resolve(cam("0.0.0.0")));
    }

    @Test
    void cloudZeroWithoutCache_returnsNull() {
        HostResolver r = new HostResolver(dir);
        assertNull(r.resolve(cam("0.0.0.0")));
        assertNull(r.resolve(cam("")));
    }

    @Test
    void systemPropertyOverride_winsOverEverything() {
        // 即使云端有效，手动指定也优先
        System.setProperty("invr.host." + DID, "192.168.1.99");
        assertEquals("192.168.1.99", new HostResolver(dir).resolve(cam("10.10.10.14")));
        // 云端 0.0.0.0 且无缓存时同样生效
        Path empty = dir.resolve("empty");
        assertEquals("192.168.1.99", new HostResolver(empty).resolve(cam("0.0.0.0")));
    }

    @Test
    void validIp_rejectsPlaceholder() {
        assertFalse(HostResolver.isValid("0.0.0.0"));
        assertFalse(HostResolver.isValid(""));
        assertFalse(HostResolver.isValid("   "));
        assertFalse(HostResolver.isValid(null));
        assertTrue(HostResolver.isValid("10.10.10.14"));
    }
}
