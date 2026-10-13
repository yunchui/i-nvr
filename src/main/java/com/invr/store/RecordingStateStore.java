package com.invr.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * “需要持续录像”的期望状态持久化。
 *
 * <p>位于数据目录下的 {@code recordings.json}（默认运行目录 {@code .invr/}，见 {@link DataDir}），
 * 结构为 {@code { "<did>": 分段秒数, ... }}。
 * 用户一旦对某摄像头点了“开始录像”就写入，只有显式“停止录像”才移除；服务正常关闭、崩溃、
 * 断电都不会清除它——启动后由对账组件据此自动恢复录像（配合 fMP4，崩溃仅丢最后约一个 GOP）。</p>
 *
 * <p>内容仅含设备 DID 与分段时长，非敏感数据，不加密；写入采用“临时文件 + 原子 rename”，
 * 避免崩溃瞬间写坏导致整份状态丢失。</p>
 */
public class RecordingStateStore {

    private static final Logger log = LoggerFactory.getLogger(RecordingStateStore.class);

    private static final String STATE_FILE = "recordings.json";

    private static final TypeReference<LinkedHashMap<String, Long>> MAP_TYPE = new TypeReference<>() {};

    private final Path dir;
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public RecordingStateStore() {
        this(DataDir.get());
    }

    public RecordingStateStore(Path dir) {
        this.dir = dir;
        this.file = dir.resolve(STATE_FILE);
    }

    /** 读取需要持续录像的 did → 分段秒数；文件不存在或损坏时返回空映射（不阻断启动）。 */
    public synchronized Map<String, Long> cameras() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return new LinkedHashMap<>();
            }
            Map<String, Long> m = mapper.readValue(bytes, MAP_TYPE);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (IOException e) {
            log.warn("录像状态文件读取失败，按无持续录像处理：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 标记某摄像头需要持续录像（含分段秒数）。 */
    public synchronized void enable(String did, long segmentSec) {
        Map<String, Long> m = cameras();
        Long prev = m.put(did, segmentSec);
        if (prev == null || prev != segmentSec) {
            persist(m);
        }
    }

    /** 显式停止录像后移除（崩溃 / 重启不经过这里，故状态得以保留并在启动后恢复）。 */
    public synchronized void disable(String did) {
        Map<String, Long> m = cameras();
        if (m.remove(did) != null) {
            persist(m);
        }
    }

    private void persist(Map<String, Long> m) {
        try {
            Files.createDirectories(dir);
            Path tmp = file.resolveSibling(STATE_FILE + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("录像状态保存失败：{}", e.getMessage());
        }
    }

    /** 仅供测试 / 诊断：不可变快照。 */
    public Map<String, Long> snapshot() {
        return Collections.unmodifiableMap(cameras());
    }
}
