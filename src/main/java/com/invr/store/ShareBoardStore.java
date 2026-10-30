package com.invr.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invr.model.ShareBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享看板持久化与内存索引。
 *
 * <p>落盘数据目录（默认 {@code .invr/}，见 {@link DataDir}）下 {@code boards.json}，
 * 写入采用“临时文件 + 原子 rename”，并尽量收紧文件权限（内含 shareToken / webhookSecret）。
 * 内存中按 id 维护 {@link ConcurrentHashMap}，另提供按 token / secret 的反查；
 * 凭据比较使用 {@link MessageDigest#isEqual} 恒定时间比较。</p>
 */
@Component
public class ShareBoardStore {

    private static final Logger log = LoggerFactory.getLogger(ShareBoardStore.class);

    private static final String BOARDS_FILE = "boards.json";
    private static final int ID_BYTES = 12;
    private static final int SECRET_BYTES = 32;

    private final Path dir;
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    private final ConcurrentHashMap<String, ShareBoard> boards = new ConcurrentHashMap<>();

    public ShareBoardStore() {
        this(DataDir.get());
    }

    public ShareBoardStore(Path dir) {
        this.dir = dir;
        this.file = dir.resolve(BOARDS_FILE);
        load();
    }

    /* ===================== 读取与反查 ===================== */

    /** 全部看板（按 id 顺序的快照）。 */
    public List<ShareBoard> all() {
        return new ArrayList<>(boards.values());
    }

    public ShareBoard findById(String id) {
        return id == null ? null : boards.get(id);
    }

    /** 按共享令牌反查；遍历 + 恒定时间比较，未命中返回 null。 */
    public ShareBoard findByToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        byte[] given = token.getBytes(StandardCharsets.UTF_8);
        for (ShareBoard b : boards.values()) {
            byte[] expected = b.shareToken == null ? null : b.shareToken.getBytes(StandardCharsets.UTF_8);
            if (expected != null && expected.length == given.length && MessageDigest.isEqual(expected, given)) {
                return b;
            }
        }
        return null;
    }

    /** 按 Webhook 密钥反查；遍历 + 恒定时间比较，未命中返回 null。 */
    public ShareBoard findBySecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        byte[] given = secret.getBytes(StandardCharsets.UTF_8);
        for (ShareBoard b : boards.values()) {
            byte[] expected = b.webhookSecret == null ? null : b.webhookSecret.getBytes(StandardCharsets.UTF_8);
            if (expected != null && expected.length == given.length && MessageDigest.isEqual(expected, given)) {
                return b;
            }
        }
        return null;
    }

    /* ===================== 写入与删除 ===================== */

    /** 保存（新增或更新）看板并全量持久化。 */
    public synchronized void save(ShareBoard board) {
        boards.put(board.id, board);
        persist();
    }

    /** 删除看板并持久化；返回被移除的看板（不存在则 null）。 */
    public synchronized ShareBoard remove(String id) {
        ShareBoard removed = boards.remove(id);
        if (removed != null) {
            persist();
        }
        return removed;
    }

    /* ===================== 凭据生成 ===================== */

    /** 生成看板内部 id（12 字节随机 base64url）。 */
    public String newId() {
        return random(ID_BYTES);
    }

    /** 生成共享令牌 / Webhook 密钥（32 字节随机 base64url，256 bit 熵）。 */
    public String newSecret() {
        return random(SECRET_BYTES);
    }

    private String random(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /* ===================== 落盘与加载 ===================== */

    private synchronized void persist() {
        try {
            Files.createDirectories(dir);
            Path tmp = file.resolveSibling(BOARDS_FILE + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("boards", new ArrayList<>(boards.values()))));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictOwnerOnly(file.toFile());
        } catch (Exception e) {
            throw new IllegalStateException("共享看板保存失败：" + e.getMessage(), e);
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return;
            }
            BoardsFile parsed = mapper.readValue(bytes, BoardsFile.class);
            if (parsed != null && parsed.boards != null) {
                for (ShareBoard b : parsed.boards) {
                    if (b == null || b.id == null || b.shareToken == null || b.webhookSecret == null) {
                        log.warn("boards.json 存在不完整记录，已跳过");
                        continue;
                    }
                    boards.put(b.id, b);
                }
            }
            log.info("已加载 {} 个共享看板", boards.size());
        } catch (Exception e) {
            log.warn("共享看板文件读取失败，以空列表启动：{}", e.getMessage());
        }
    }

    /** 尽量收紧文件权限（Windows 上仅尽力而为），对齐 AccessKeyStore。 */
    private void restrictOwnerOnly(File f) {
        f.setReadable(false, false);
        f.setWritable(false, false);
        f.setReadable(true, true);
        f.setWritable(true, true);
    }

    /** boards.json 顶层结构。 */
    public static class BoardsFile {
        public List<ShareBoard> boards;
    }
}
