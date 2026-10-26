package com.invr.web.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 访问密码门禁的“无 Cookie”令牌会话（Bearer Token Session）。
 *
 * <p><b>背景</b>：当 Web 控制台被跨站页面以 iframe 嵌入（例如 {@code https} 门户内嵌
 * {@code http://内网IP:端口}）时，基于 JSESSIONID Cookie 的会话无法工作——跨站 iframe 中的
 * Cookie 必须带 {@code SameSite=None}，而该属性又强制 {@code Secure}，Secure Cookie 在 HTTP
 * 连接上会被浏览器直接丢弃；Safari 等浏览器则直接封禁第三方 Cookie。为彻底摆脱对 Cookie 的
 * 依赖，门禁会话改为不透明令牌：前端在 {@code Authorization: Bearer <token>} 头中携带；
 * 无法加自定义请求头的媒体请求（{@code <video>}、下载链接）用 {@code ?__invr_t=<token>}
 * 查询参数携带。</p>
 *
 * <p>令牌保存在浏览器 localStorage（iframe 中为按顶级站点分区的存储，仍可读写），服务端只在
 * 内存维护令牌→解锁态，进程重启即失效（需重新解锁，与原 HttpSession 行为一致），不落盘、
 * 不涉及米家凭据。</p>
 *
 * <p>会话采用滑动过期（默认 30 分钟无活动，对齐容器 HttpSession 默认超时），并设最大数量
 * 上限，防止匿名刷接口撑爆内存。</p>
 */
@Component
public class TokenSessionManager {

    private static final Logger log = LoggerFactory.getLogger(TokenSessionManager.class);

    /** 会话不活动超时（滑动过期）。 */
    private static final long IDLE_TTL_MS = 30 * 60_000L;
    /** 会话数上限：超出时淘汰最久未活动的一条，防止匿名刷 status 撑爆内存。 */
    private static final int MAX_SESSIONS = 20_000;
    /** 过期会话巡检周期。 */
    private static final long CLEAN_PERIOD_SECONDS = 5 * 60;

    private final Map<String, TokenSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder tokenEncoder = Base64.getUrlEncoder().withoutPadding();
    private final ScheduledExecutorService executor;

    public TokenSessionManager() {
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "token-session-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.executor.scheduleWithFixedDelay(this::evictExpired,
                CLEAN_PERIOD_SECONDS, CLEAN_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /** 创建一条全新（未解锁）的令牌会话。 */
    public TokenSession create() {
        enforceLimit();
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        TokenSession s = new TokenSession(tokenEncoder.encodeToString(buf));
        sessions.put(s.getToken(), s);
        return s;
    }

    /** 按令牌取会话；不存在或已过期返回 null（过期顺手删除）。命中时刷新活动时间。 */
    public TokenSession resolve(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        TokenSession s = sessions.get(token);
        if (s == null) {
            return null;
        }
        if (System.currentTimeMillis() - s.getLastAccessMs() > IDLE_TTL_MS) {
            sessions.remove(token);
            return null;
        }
        s.touch();
        return s;
    }

    /** 主动删除一条会话。 */
    public void remove(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private void enforceLimit() {
        if (sessions.size() < MAX_SESSIONS) {
            return;
        }
        String oldestToken = null;
        long oldestMs = Long.MAX_VALUE;
        for (TokenSession s : sessions.values()) {
            if (s.getLastAccessMs() < oldestMs) {
                oldestMs = s.getLastAccessMs();
                oldestToken = s.getToken();
            }
        }
        if (oldestToken != null) {
            sessions.remove(oldestToken);
            log.debug("令牌会话数达上限 {}，已淘汰最久未活动会话", MAX_SESSIONS);
        }
    }

    private void evictExpired() {
        try {
            long now = System.currentTimeMillis();
            sessions.entrySet().removeIf(e -> now - e.getValue().getLastAccessMs() > IDLE_TTL_MS);
        } catch (Throwable t) {
            // 清理任务里绝不让异常逃逸，否则 ScheduledExecutor 会取消后续周期。
            log.debug("令牌会话清理失败，下轮重试：{}", t.toString());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 一条浏览器门禁会话：仅承载门禁解锁态（米家登录态是进程级单例，不在此保存）。 */
    public static final class TokenSession {
        private final String token;
        private final long createdAtMs;
        private volatile long lastAccessMs;
        private volatile boolean unlocked;

        TokenSession(String token) {
            this.token = token;
            this.createdAtMs = System.currentTimeMillis();
            this.lastAccessMs = this.createdAtMs;
        }

        public String getToken() {
            return token;
        }

        public long getCreatedAtMs() {
            return createdAtMs;
        }

        public long getLastAccessMs() {
            return lastAccessMs;
        }

        public boolean isUnlocked() {
            return unlocked;
        }

        public void setUnlocked(boolean unlocked) {
            this.unlocked = unlocked;
        }

        void touch() {
            this.lastAccessMs = System.currentTimeMillis();
        }
    }
}
