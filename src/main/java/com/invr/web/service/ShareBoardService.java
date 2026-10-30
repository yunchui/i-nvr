package com.invr.web.service;

import com.invr.model.ShareBoard;
import com.invr.store.ShareBoardStore;
import com.invr.web.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 共享看板业务逻辑：CRUD、布局与摄像头校验、启停、令牌/密钥轮换、可用性判定。
 *
 * <p>持久化委托 {@link ShareBoardStore}，设备存在性校验依赖 {@link DeviceCatalog}。
 * 凭据轮换不影响另一条凭据（轮换 token 不动 secret，反之亦然）。</p>
 */
@Service
public class ShareBoardService {

    private static final Logger log = LoggerFactory.getLogger(ShareBoardService.class);

    private final ShareBoardStore store;
    private final DeviceCatalog catalog;
    private final ApplicationEventPublisher publisher;

    public ShareBoardService(ShareBoardStore store, DeviceCatalog catalog,
                             ApplicationEventPublisher publisher) {
        this.store = store;
        this.catalog = catalog;
        this.publisher = publisher;
    }

    public List<ShareBoard> list() {
        return store.all();
    }

    public ShareBoard get(String id) {
        ShareBoard b = store.findById(id);
        if (b == null) {
            throw ApiException.notFound("共享看板不存在：" + id);
        }
        return b;
    }

    /** 新建看板：服务端生成 id、shareToken、webhookSecret。 */
    public ShareBoard create(String name, String layout, List<String> cameras, Long expireAt) {
        String cleanName = validateName(name);
        validateLayout(layout);
        validateCameras(layout, cameras);
        long now = System.currentTimeMillis();

        ShareBoard b = new ShareBoard();
        b.id = store.newId();
        b.name = cleanName;
        b.layout = layout;
        b.cameras = normalizeCameras(cameras);
        b.shareToken = store.newSecret();
        b.webhookSecret = store.newSecret();
        b.expireAt = normalizeExpire(expireAt);
        b.enabled = true;
        b.createdAt = now;
        b.updatedAt = now;
        store.save(b);
        log.info("共享看板已创建 id={} layout={} 摄像头数={}", b.id, b.layout, b.cameras.size());
        return b;
    }

    /** 更新名称/布局/摄像头/过期时间；保留原 token、secret 与启用状态。 */
    public ShareBoard update(String id, String name, String layout, List<String> cameras, Long expireAt) {
        ShareBoard b = get(id);
        b.name = validateName(name);
        validateLayout(layout);
        validateCameras(layout, cameras);
        b.layout = layout;
        b.cameras = normalizeCameras(cameras);
        b.expireAt = normalizeExpire(expireAt);
        b.updatedAt = System.currentTimeMillis();
        store.save(b);
        log.info("共享看板已更新 id={} layout={}", id, layout);
        return b;
    }

    public ShareBoard setEnabled(String id, boolean enabled) {
        ShareBoard b = get(id);
        b.enabled = enabled;
        b.updatedAt = System.currentTimeMillis();
        store.save(b);
        if (!enabled) {
            publisher.publishEvent(new BoardLifecycleEvent(id));
        }
        log.info("共享看板 {} 已{}", id, enabled ? "启用" : "禁用");
        return b;
    }

    /** 轮换共享令牌，旧共享链接立即失效；webhookSecret 不变。 */
    public ShareBoard rotateToken(String id) {
        ShareBoard b = get(id);
        b.shareToken = store.newSecret();
        b.updatedAt = System.currentTimeMillis();
        store.save(b);
        publisher.publishEvent(new BoardLifecycleEvent(id));
        log.info("共享看板 {} 已轮换共享令牌", id);
        return b;
    }

    /** 轮换 Webhook 密钥，外部系统需同步更新；shareToken 不变。 */
    public ShareBoard rotateSecret(String id) {
        ShareBoard b = get(id);
        b.webhookSecret = store.newSecret();
        b.updatedAt = System.currentTimeMillis();
        store.save(b);
        log.info("共享看板 {} 已轮换 Webhook 密钥", id);
        return b;
    }

    public void delete(String id) {
        if (store.remove(id) == null) {
            throw ApiException.notFound("共享看板不存在：" + id);
        }
        publisher.publishEvent(new BoardLifecycleEvent(id));
        log.info("共享看板已删除 id={}", id);
    }

    /* ===================== 免鉴权路径解析（M2/M3 使用） ===================== */

    /** 按共享令牌解析看板，并校验启用与过期；不可用返回 null。 */
    public ShareBoard resolveByToken(String token) {
        ShareBoard b = store.findByToken(token);
        return b != null && isAvailable(b) ? b : null;
    }

    /** 按 Webhook 密钥解析看板（不校验启用/过期，由调用方区分错误类型）。 */
    public ShareBoard resolveBySecret(String secret) {
        return store.findBySecret(secret);
    }

    /** 看板是否处于可用状态：启用且未过期。 */
    public boolean isAvailable(ShareBoard b) {
        return b.enabled && (b.expireAt == null || b.expireAt > System.currentTimeMillis());
    }

    /* ===================== 校验 ===================== */

    private String validateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw ApiException.badRequest("看板名称不能为空");
        }
        return name.trim();
    }

    private void validateLayout(String layout) {
        if (!slotCount(layout).isPresent()) {
            throw ApiException.badRequest("非法布局类型：" + layout);
        }
    }

    private void validateCameras(String layout, List<String> cameras) {
        int expected = slotCount(layout).orElseThrow();
        if (cameras == null || cameras.size() != expected) {
            throw ApiException.badRequest("该布局需要 " + expected + " 个摄像头，实际 "
                    + (cameras == null ? 0 : cameras.size()) + " 个");
        }
        Set<String> seen = new HashSet<>();
        for (String raw : cameras) {
            // 空元素表示该槽位空置，允许
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String did = raw.trim();
            if (!seen.add(did)) {
                throw ApiException.badRequest("摄像头不能重复：" + did);
            }
            if (catalog.find(did).isEmpty()) {
                throw ApiException.badRequest("设备列表中不存在 did=" + did + "，请先刷新设备");
            }
        }
    }

    /** 把空字符串规范化为 null、对 did 去空白，保持每个槽位一位（空置为 null）。 */
    private List<String> normalizeCameras(List<String> cameras) {
        return cameras.stream()
                .map(raw -> (raw == null || raw.trim().isEmpty()) ? null : raw.trim())
                .toList();
    }

    private Long normalizeExpire(Long expireAt) {
        if (expireAt == null || expireAt <= 0) {
            return null;
        }
        if (expireAt <= System.currentTimeMillis()) {
            throw ApiException.badRequest("过期时间不能早于当前时间");
        }
        return expireAt;
    }

    /** 布局类型 → 槽位数；非法布局返回 empty。 */
    public static java.util.Optional<Integer> slotCount(String layout) {
        return switch (layout) {
            case ShareBoard.LAYOUT_GRID4 -> java.util.Optional.of(4);
            case ShareBoard.LAYOUT_GRID6 -> java.util.Optional.of(6);
            case ShareBoard.LAYOUT_GRID9 -> java.util.Optional.of(9);
            default -> java.util.Optional.empty();
        };
    }
}
