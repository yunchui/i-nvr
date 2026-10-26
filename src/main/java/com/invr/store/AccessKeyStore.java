package com.invr.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Web 控制台“访问密码”的本地存储（独立于米家账号登录）。
 *
 * <p>访问密码用于进入本系统的本地门禁，<b>不保存明文、也不可逆</b>：采用
 * {@code PBKDF2WithHmacSHA256} + 每密码独立随机盐 + 高迭代次数派生密钥，落盘
 * 数据目录下的 {@code access.json}（默认运行目录 {@code .invr/}，见 {@link DataDir}；
 * base64 的 salt / hash + 迭代次数）。校验时用相同参数
 * 重新派生并做恒定时间比较。</p>
 *
 * <p>该文件与 {@link SessionStore} 的 master.key/session.json 同目录，并尽量收紧为仅
 * owner 可读写。它防的是“随手打开看到密码 / 离线字典直读”，不抵抗拿到整个目录且能离线
 * 高速穷举弱口令的攻击者，因此仍要求密码不少于 {@value #MIN_LEN} 位。</p>
 */
@Component
public class AccessKeyStore {

    private static final Logger log = LoggerFactory.getLogger(AccessKeyStore.class);

    private static final String ACCESS_FILE = "access.json";
    private static final String ALGO = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int ITERATIONS = 120_000;
    public static final int MIN_LEN = 6;
    public static final int MAX_LEN = 64;

    /** URL 一键解锁令牌派生的域分隔前缀（避免与其它 SHA-256 用途混淆）。 */
    private static final String UNLOCK_DOMAIN = "invr:url-unlock/v1:";

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final SecureRandom random = new SecureRandom();

    /** null 表示尚未设置访问密码。 */
    private volatile Record record;

    public AccessKeyStore() {
        this(DataDir.get());
    }

    public AccessKeyStore(Path dir) {
        this.dir = dir;
        this.record = load();
    }

    public boolean isConfigured() {
        return record != null;
    }

    /**
     * 首次设置访问密码。
     *
     * @throws IllegalStateException 已设置过（不允许覆盖，需先删除 access.json）
     * @throws IllegalArgumentException 密码不满足强度要求
     */
    public synchronized void setup(char[] password) {
        if (record != null) {
            throw new IllegalStateException("访问密码已设置，不能重复设置");
        }
        String bad = validate(password);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);

        Record r = new Record();
        r.algo = ALGO;
        r.iterations = ITERATIONS;
        r.salt = Base64.getEncoder().encodeToString(salt);
        r.hash = Base64.getEncoder().encodeToString(hash);
        r.urlHash = urlTokenHashB64(password);
        persist(r);
        record = r;
        log.info("访问密码已设置（PBKDF2 迭代 {}）", ITERATIONS);
    }

    /** 校验访问密码；未设置或参数/算法异常一律返回 false（不泄露具体原因）。 */
    public synchronized boolean verify(char[] password) {
        Record r = record;
        if (r == null || password == null || password.length == 0) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(r.salt);
            byte[] expected = Base64.getDecoder().decode(r.hash);
            byte[] actual = pbkdf2(password, salt, r.iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            log.warn("访问密码校验异常：{}", e.getMessage());
            return false;
        }
    }

    /* ===================== URL 一键解锁令牌 ===================== */

    /**
     * 由访问密码派生 URL 解锁令牌：{@code base64url(SHA-256(UNLOCK_DOMAIN + password))}。
     * 确定性、无随机盐，便于凭密码随时生成同一条链接；令牌不含也不暴露原密码。
     */
    public static String buildUnlockToken(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("密码为空");
        }
        byte[] digest = sha256((UNLOCK_DOMAIN + new String(password)).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    /** 校验 URL 携带的解锁令牌（对其二次哈希做恒定时间比较）；未启用/损坏/为空一律 false。 */
    public synchronized boolean verifyUnlockToken(String token) {
        Record r = record;
        if (r == null || r.urlHash == null || token == null || token.isEmpty()) {
            return false;
        }
        try {
            byte[] expected = Base64.getDecoder().decode(r.urlHash);
            byte[] actual = sha256(token.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 校验访问密码通过后返回解锁令牌；若为旧版记录（无 urlHash）则补算并落盘。
     * 密码错误抛 {@link IllegalArgumentException}，未设置抛 {@link IllegalStateException}。
     */
    public synchronized String regenerateUnlockToken(char[] password) {
        Record r = record;
        if (r == null) {
            throw new IllegalStateException("尚未设置访问密码");
        }
        if (!verify(password)) {
            throw new IllegalArgumentException("访问密码错误");
        }
        String expectedHash = urlTokenHashB64(password);
        if (!expectedHash.equals(r.urlHash)) {
            r.urlHash = expectedHash;
            persist(r);
        }
        return buildUnlockToken(password);
    }

    /**
     * 在已通过门禁的会话内修改访问密码：先校验当前密码，再用<strong>新随机盐</strong>重新派生落盘。
     * 成功后返回新的一键解锁令牌（密码改变后旧令牌随之失效）；当前会话的解锁态不受影响。
     *
     * @throws IllegalStateException 尚未设置访问密码
     * @throws IllegalArgumentException 当前密码错误、新密码不合规或与旧密码相同
     */
    public synchronized String changePassword(char[] oldPassword, char[] newPassword) {
        Record r = record;
        if (r == null) {
            throw new IllegalStateException("尚未设置访问密码");
        }
        if (oldPassword == null || oldPassword.length == 0 || !verify(oldPassword)) {
            throw new IllegalArgumentException("当前密码错误");
        }
        String bad = validate(newPassword);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        // 新密码与当前密码相同则拒绝（用旧盐派生后做恒定时间比较）
        byte[] oldSalt = Base64.getDecoder().decode(r.salt);
        byte[] oldHash = Base64.getDecoder().decode(r.hash);
        if (MessageDigest.isEqual(oldHash, pbkdf2(newPassword, oldSalt, r.iterations))) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(newPassword, salt, ITERATIONS);

        Record nr = new Record();
        nr.algo = ALGO;
        nr.iterations = ITERATIONS;
        nr.salt = Base64.getEncoder().encodeToString(salt);
        nr.hash = Base64.getEncoder().encodeToString(hash);
        nr.urlHash = urlTokenHashB64(newPassword);
        persist(nr);
        record = nr;
        log.info("访问密码已修改（PBKDF2 迭代 {}）", ITERATIONS);
        return buildUnlockToken(newPassword);
    }

    /** 落盘的是“令牌的 SHA-256”，避免 access.json 中直接存放可立即使用的解锁令牌。 */
    private static String urlTokenHashB64(char[] password) {
        String token = buildUnlockToken(password);
        return Base64.getEncoder().encodeToString(
                sha256(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 强度校验，返回 null 表示通过，否则返回可读原因。 */
    public static String validate(char[] pw) {
        if (pw == null || pw.length < MIN_LEN) {
            return "访问密码至少 " + MIN_LEN + " 位";
        }
        if (pw.length > MAX_LEN) {
            return "访问密码不能超过 " + MAX_LEN + " 位";
        }
        for (char c : pw) {
            if (!Character.isWhitespace(c)) {
                return null;
            }
        }
        return "访问密码不能全为空白字符";
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            try {
                SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGO);
                return factory.generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("派生访问密码密钥失败：" + e.getMessage(), e);
        }
    }

    private synchronized Record load() {
        Path file = dir.resolve(ACCESS_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Record r = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Record.class);
            if (r == null || r.salt == null || r.hash == null || r.iterations <= 0) {
                log.warn("访问密码文件内容不完整，视为未设置：{}", file);
                return null;
            }
            return r;
        } catch (Exception e) {
            log.warn("读取访问密码失败，视为未设置（{}）：{}", file, e.getMessage());
            return null;
        }
    }

    private synchronized void persist(Record r) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(ACCESS_FILE);
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(r), StandardCharsets.UTF_8);
            restrictOwnerOnly(file);
        } catch (Exception e) {
            throw new IllegalStateException("保存访问密码失败：" + e.getMessage(), e);
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

    /** access.json 映射结构。 */
    public static class Record {
        public String algo;
        public int iterations;
        public String salt;
        public String hash;
        /** URL 解锁令牌的二次 SHA-256（标准 base64）；旧版本文件可能为 null。 */
        public String urlHash;
    }
}
