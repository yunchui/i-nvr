package com.invr.wall.config;

import com.invr.wall.support.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * wall-config.json 持久化，风格对齐服务端 DataDir/Store：
 * 解析优先级 -Dinvr.wall.data.dir &gt; 环境变量 INVR_WALL_DATA_DIR &gt; 运行目录/.invr-wall。
 * 写操作先落临时文件再原子替换；文件权限收紧为仅 owner 可读写（令牌敏感）。
 */
public final class WallConfigStore {

    private static final Logger log = LoggerFactory.getLogger(WallConfigStore.class);
    private static final String DIR_NAME = ".invr-wall";
    private static final String FILE_NAME = "wall-config.json";

    private final Path file;

    public WallConfigStore() {
        this.file = dataDir().resolve(FILE_NAME);
    }

    public static Path dataDir() {
        String override = System.getProperty("invr.wall.data.dir");
        if (override == null || override.isBlank()) {
            override = System.getenv("INVR_WALL_DATA_DIR");
        }
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim());
        }
        return Paths.get(System.getProperty("user.dir"), DIR_NAME);
    }

    /** 不存在或解析失败返回 null（首次启动进入设置页）。 */
    public WallConfig load() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            WallConfig c = Json.MAPPER.readValue(bytes, WallConfig.class);
            if (c == null || c.baseUrl == null || c.token == null) {
                log.warn("配置文件字段不完整，将进入设置页：{}", file);
                return null;
            }
            return c;
        } catch (Exception e) {
            log.error("读取配置失败，将进入设置页：{}", e.toString());
            return null;
        }
    }

    public synchronized void save(WallConfig config) {
        try {
            Path dir = file.getParent();
            Files.createDirectories(dir);
            Path tmp = dir.resolve(FILE_NAME + ".tmp");
            Files.writeString(tmp, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config),
                    StandardCharsets.UTF_8);
            try {
                File f = tmp.toFile();
                f.setReadable(false, false);
                f.setWritable(false, false);
                f.setReadable(true, true);
                f.setWritable(true, true);
            } catch (SecurityException ignore) {
                // 文件系统不支持 POSIX 权限时忽略
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("配置已保存到 {}", file);
        } catch (Exception e) {
            throw new IllegalStateException("配置保存失败：" + e.getMessage(), e);
        }
    }

    public Path path() {
        return file;
    }
}
