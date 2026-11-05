package com.invr.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 开放接口 API 密钥的本地存储（独立于 Web 门禁访问密码）。
 *
 * <p>用于外部系统调用受 API 密钥保护的开放接口（当前为 {@code GET /api/snapshot/{did}}，
 * 请求头 {@code X-API-Key}）。<b>不落明文</b>：文件中只保存密钥的 SHA-256 摘要与用于页面
 * 展示的脱敏掩码，校验时对请求密钥做同样摘要并恒定时间比较。随机密钥为
 * {@code invr_} + 32 位 base64url 字符（24 字节随机量，约 128 位熵），仅在生成/保存的
 * 响应中返回一次明文。</p>
 *
 * <p>数据文件 {@code api-key.json} 与 {@link AccessKeyStore} 的 access.json 同目录（默认
 * 运行目录 {@code .invr/}，见 {@link DataDir}），并尽量收紧为仅 owner 可读写。SHA-256 对
 * 高熵随机密钥足够；自定义密钥因此强制不少于 {@value #MIN_CUSTOM_LEN} 位且不得含空白。</p>
 */
@Component
public class ApiKeyStore {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyStore.class);

    private static final String API_KEY_FILE = "api-key.json";
    /** 随机密钥前缀，便于在日志/配置中辨识。 */
    public static final String KEY_PREFIX = "invr_";
    /** 随机字节数（24 → base64url 32 字符）。 */
    private static final int RANDOM_BYTES = 24;
    /** 自定义密钥长度边界。 */
    public static final int MIN_CUSTOM_LEN = 16;
    public static final int MAX_LEN = 80;
    /** 掩码保留的前缀（含 invr_）与尾段字符数。 */
    private static final int MASK_HEAD = 9;
    private static final int MASK_TAIL = 4;

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final SecureRandom random = new SecureRandom();

    private volatile Record record;

    public ApiKeyStore() {
        this(DataDir.get());
    }

    public ApiKeyStore(Path dir) {
        this.dir = dir;
        this.record = load();
    }

    /** 是否已配置 API 密钥。 */
    public boolean isConfigured() {
        return record != null;
    }

    /** 当前密钥的脱敏信息（不暴露任何可用于调用的片段）。 */
    public synchronized Info info() {
        Record r = record;
        if (r == null) {
            return new Info(false, null, null);
        }
        return new Info(true, r.mask, r.createdAt);
    }

    /**
     * 生成一条新的随机密钥并落盘（替换旧密钥，旧密钥立即失效）。
     *
     * @return 仅此次返回的明文密钥，页面只展示一次
     */
    public synchronized String generate() {
        byte[] raw = new byte[RANDOM_BYTES];
        random.nextBytes(raw);
        String key = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        persistKey(key);
        log.info("已生成新的 API 开放接口密钥（旧密钥同步失效）");
        return key;
    }

    /**
     * 保存调用方自定义的密钥（替换旧密钥）。
     *
     * @throws IllegalArgumentException 密钥为空、含空白或长度不合规
     */
    public synchronized String saveCustom(String key) {
        String normalized = validateCustom(key);
        persistKey(normalized);
        log.info("已保存自定义 API 开放接口密钥（旧密钥同步失效）");
        return normalized;
    }

    /**
     * 恒定时间校验请求携带的密钥；未配置、空值或不匹配一律 false。
     */
    public synchronized boolean verify(String presented) {
        Record r = record;
        if (r == null || presented == null || presented.isEmpty()) {
            return false;
        }
        try {
            byte[] expected = Base64.getDecoder().decode(r.keyHash);
            byte[] actual = sha256(presented.trim().getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            log.warn("API 密钥校验异常：{}", e.getMessage());
            return false;
        }
    }

    /** 删除密钥（停用开放接口的密钥访问方式）。 */
    public synchronized void clear() {
        record = null;
        try {
            Files.deleteIfExists(dir.resolve(API_KEY_FILE));
            log.info("API 开放接口密钥已删除");
        } catch (Exception e) {
            throw new IllegalStateException("删除 API 密钥失败：" + e.getMessage(), e);
        }
    }

    /** 自定义密钥强度校验，返回 trim 后的密钥。 */
    public static String validateCustom(String key) {
        if (key == null) {
            throw new IllegalArgumentException("API 密钥不能为空");
        }
        String s = key.trim();
        if (s.length() < MIN_CUSTOM_LEN) {
            throw new IllegalArgumentException("API 密钥至少 " + MIN_CUSTOM_LEN + " 位");
        }
        if (s.length() > MAX_LEN) {
            throw new IllegalArgumentException("API 密钥不能超过 " + MAX_LEN + " 位");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c < '!' || c > '~') {
                throw new IllegalArgumentException("API 密钥只能使用非空白的可见 ASCII 字符");
            }
        }
        return s;
    }

    private void persistKey(String key) {
        Record r = new Record();
        r.keyHash = Base64.getEncoder().encodeToString(
                sha256(key.getBytes(StandardCharsets.UTF_8)));
        r.mask = maskOf(key);
        r.createdAt = System.currentTimeMillis();
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(API_KEY_FILE);
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(r), StandardCharsets.UTF_8);
            restrictOwnerOnly(file);
            record = r;
        } catch (Exception e) {
            throw new IllegalStateException("保存 API 密钥失败：" + e.getMessage(), e);
        }
    }

    /** 脱敏掩码：{@code invr_xxxx…yyyy}，仅保留首段与末 4 位。 */
    static String maskOf(String key) {
        if (key.length() <= MASK_HEAD + MASK_TAIL) {
            return KEY_PREFIX + "****";
        }
        return key.substring(0, MASK_HEAD) + "…" + key.substring(key.length() - MASK_TAIL);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private synchronized Record load() {
        Path file = dir.resolve(API_KEY_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Record r = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Record.class);
            if (r == null || r.keyHash == null || r.keyHash.isBlank()) {
                log.warn("API 密钥文件内容不完整，视为未配置：{}", file);
                return null;
            }
            return r;
        } catch (Exception e) {
            log.warn("读取 API 密钥失败，视为未配置（{}）：{}", file, e.getMessage());
            return null;
        }
    }

    /** 尽量收紧文件权限（Windows 上仅尽力而为）。 */
    private void restrictOwnerOnly(Path path) {
        File file = path.toFile();
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    /** 对页面返回的脱敏状态。 */
    public record Info(boolean configured, String mask, Long createdAt) {
    }

    /** api-key.json 映射结构（仅存摘要，不含明文）。 */
    public static class Record {
        public String keyHash;
        public String mask;
        public Long createdAt;
    }
}
