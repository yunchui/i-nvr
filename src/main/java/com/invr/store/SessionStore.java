package com.invr.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invr.cloud.LoginSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 会话凭据本地加密存储。
 *
 * <p>位于数据目录（默认运行目录下 {@code .invr/}，见 {@link DataDir}）：
 * <ul>
 *   <li>master.key：安装/首次运行生成的 32 字节随机主密钥（仅 owner 可读写）；</li>
 *   <li>session.json：AES-GCM 加密后的 LoginSession（base64，含 12 字节随机 IV）。</li>
 * </ul>
 * 注意：master.key 与密文同目录，可防止随手打开看到明文，但不能抵抗拿到整个目录的攻击者。
 * 后续可升级为主密钥由用户设置的管理密码派生（PBKDF2）。
 */
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private static final String MASTER_FILE = "master.key";
    private static final String SESSION_FILE = "session.json";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public SessionStore() {
        this(DataDir.get());
    }

    public SessionStore(Path dir) {
        this.dir = dir;
    }

    public synchronized void save(LoginSession session) {
        try {
            Files.createDirectories(dir);
            byte[] masterKey = loadOrCreateMasterKey();
            byte[] plain = mapper.writeValueAsBytes(session);

            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain);

            byte[] blob = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(encrypted, 0, blob, iv.length, encrypted.length);
            Files.writeString(dir.resolve(SESSION_FILE), Base64.getEncoder().encodeToString(blob));
            restrictOwnerOnly(dir.resolve(SESSION_FILE));
            log.debug("会话已保存至 {}", dir.resolve(SESSION_FILE));
        } catch (Exception e) {
            throw new IllegalStateException("保存会话失败：" + e.getMessage(), e);
        }
    }

    public synchronized java.util.Optional<LoginSession> load() {
        Path file = dir.resolve(SESSION_FILE);
        if (!Files.exists(file)) {
            return java.util.Optional.empty();
        }
        try {
            byte[] masterKey = loadOrCreateMasterKey();
            byte[] blob = Base64.getDecoder().decode(Files.readString(file).trim());
            byte[] iv = java.util.Arrays.copyOfRange(blob, 0, IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(blob, IV_BYTES, blob.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(encrypted);
            return java.util.Optional.of(mapper.readValue(plain, LoginSession.class));
        } catch (IOException e) {
            throw new IllegalStateException("读取会话失败：" + e.getMessage(), e);
        } catch (Exception e) {
            log.warn("会话解密失败（需重新登录）：{}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(dir.resolve(SESSION_FILE));
        } catch (IOException e) {
            throw new IllegalStateException("删除会话失败：" + e.getMessage(), e);
        }
    }

    private byte[] loadOrCreateMasterKey() throws IOException {
        Path file = dir.resolve(MASTER_FILE);
        if (Files.exists(file)) {
            byte[] key = Files.readAllBytes(file);
            if (key.length == KEY_BYTES) {
                return key;
            }
        }
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        Files.write(file, key);
        restrictOwnerOnly(file);
        return key;
    }

    /** 尽量收紧文件权限（Windows 上仅尽力而为）。 */
    private void restrictOwnerOnly(Path path) {
        File file = path.toFile();
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }
}
