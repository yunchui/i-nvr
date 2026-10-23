package com.invr.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可在 Web 端修改的应用设置持久化。
 *
 * <p>位于数据目录下的 {@code settings.json}（默认运行目录 {@code .invr/}，见 {@link DataDir}），
 * 非敏感参数，JSON 明文存储，写入采用“临时文件 + 原子 rename”。当前仅包含录像根目录：</p>
 * <ul>
 *   <li>{@code recordingDir}：用户在“设置”页指定的录像存储目录（存绝对路径）；
 *       缺省 / 为空表示沿用 {@code application.yml} 中 {@code invr.recording-dir} 的默认值。</li>
 * </ul>
 *
 * <p>Web 设置优先级高于配置文件：一旦在页面保存，重启后仍以保存值为准（见
 * {@code RecordingService} 初始化）；在页面“恢复默认”会删除该键，回到配置文件默认值。</p>
 */
public class AppSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(AppSettingsStore.class);

    private static final String SETTINGS_FILE = "settings.json";
    private static final String KEY_RECORDING_DIR = "recordingDir";
    private static final String KEY_SEGMENT_SEC = "recordSegmentSec";
    private static final String KEY_RTSP_PORT = "rtspPort";
    private static final String KEY_RETENTION_DAYS = "recordRetentionDays";

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Path dir;
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public AppSettingsStore() {
        this(DataDir.get());
    }

    public AppSettingsStore(Path dir) {
        this.dir = dir;
        this.file = dir.resolve(SETTINGS_FILE);
    }

    /** 读取全部设置；文件不存在或损坏时返回空映射（不阻断启动，按默认值处理）。 */
    public synchronized Map<String, Object> all() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> m = mapper.readValue(bytes, MAP_TYPE);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (IOException e) {
            log.warn("设置文件读取失败，按默认设置处理：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 已持久化的自定义录像目录（绝对路径）；未设置 / 为空时返回 null（沿用配置文件默认值）。 */
    public String recordingDir() {
        Object v = all().get(KEY_RECORDING_DIR);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 保存录像目录。
     *
     * @param absolutePath 绝对路径；传 null / 空白表示清除自定义、恢复配置文件默认值
     */
    public synchronized void saveRecordingDir(String absolutePath) {
        Map<String, Object> m = all();
        if (absolutePath == null || absolutePath.trim().isEmpty()) {
            m.remove(KEY_RECORDING_DIR);
        } else {
            m.put(KEY_RECORDING_DIR, absolutePath.trim());
        }
        persist(m);
    }

    /** 已持久化的自定义录像分段秒数；未设置 / 非正数时返回 null（沿用配置文件默认值）。 */
    public Integer recordSegmentSec() {
        return getInt(KEY_SEGMENT_SEC);
    }

    /** 已持久化的自定义 RTSP 端口；未设置 / 非正数时返回 null（沿用配置文件默认值）。 */
    public Integer rtspPort() {
        return getInt(KEY_RTSP_PORT);
    }

    /** 保存录像分段秒数；null / 非正数表示清除自定义、恢复默认。 */
    public void saveRecordSegmentSec(Integer sec) {
        putInt(KEY_SEGMENT_SEC, sec);
    }

    /** 保存 RTSP 端口；null / 非正数表示清除自定义、恢复默认。 */
    public void saveRtspPort(Integer port) {
        putInt(KEY_RTSP_PORT, port);
    }

    /** 已持久化的录像保留天数；未设置 / 非正数（0 表示永久保留）时返回 null，沿用配置默认。 */
    public Integer recordRetentionDays() {
        return getInt(KEY_RETENTION_DAYS);
    }

    /** 保存录像保留天数；null / 0 / 负数表示清除自定义、恢复默认（默认永久保留）。 */
    public void saveRecordRetentionDays(Integer days) {
        putInt(KEY_RETENTION_DAYS, days);
    }

    private Integer getInt(String key) {
        Object v = all().get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            int x = ((Number) v).intValue();
            return x > 0 ? x : null;
        }
        try {
            int x = Integer.parseInt(String.valueOf(v).trim());
            return x > 0 ? x : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private synchronized void putInt(String key, Integer value) {
        Map<String, Object> m = all();
        if (value == null || value <= 0) {
            m.remove(key);
        } else {
            m.put(key, value);
        }
        persist(m);
    }

    private void persist(Map<String, Object> m) {
        try {
            Files.createDirectories(dir);
            Path tmp = file.resolveSibling(SETTINGS_FILE + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 设置保存失败必须让用户感知（区别于录像状态等尽力而为的持久化），向上抛出
            throw new RuntimeException("设置保存失败：" + e.getMessage(), e);
        }
    }
}
