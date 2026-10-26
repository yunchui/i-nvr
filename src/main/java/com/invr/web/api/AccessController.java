package com.invr.web.api;

import com.invr.store.AccessKeyStore;
import com.invr.web.config.TokenSessionFilter;
import com.invr.web.service.SessionHolder;
import com.invr.web.service.TokenSessionManager;
import com.invr.web.service.TokenSessionManager.TokenSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web 控制台访问密码门禁（独立于米家账号登录）。
 *
 * <ul>
 *   <li>首次：米家账号登录成功后调用 {@code POST /api/access/setup} 设置访问密码，
 *       设置成功即在当前令牌会话解锁并进入系统；</li>
 *   <li>以后：每次打开系统（新会话/会话过期）需 {@code POST /api/access/unlock} 校验密码；</li>
 *   <li>解锁态保存在服务端 {@link TokenSession}（不依赖 Cookie），由
 *       {@code AccessInterceptor} 对所有受保护 API 与 HLS 资源强制校验；</li>
 *   <li>无令牌的 {@code /status} 请求会自动签发一条新令牌并随响应返回，前端据此保存，
 *       之后所有请求以 {@code Authorization: Bearer <token>} 或 {@code ?__invr_t=} 携带。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/access")
public class AccessController {

    private final AccessKeyStore keyStore;
    private final SessionHolder sessionHolder;
    private final TokenSessionManager tokenSessions;

    public AccessController(AccessKeyStore keyStore, SessionHolder sessionHolder,
                            TokenSessionManager tokenSessions) {
        this.keyStore = keyStore;
        this.sessionHolder = sessionHolder;
        this.tokenSessions = tokenSessions;
    }

    public record PasswordRequest(String password) {
    }

    /** 门禁状态：是否已设密码、当前会话是否已解锁、米家是否已登录。无令牌时自动签发新令牌。 */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        TokenSession session = obtainSession(request);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", keyStore.isConfigured());
        m.put("unlocked", session.isUnlocked());
        m.put("loggedIn", sessionHolder.isLoggedIn());
        sessionHolder.get().ifPresent(s -> {
            m.put("userId", s.userId());
            m.put("region", s.region());
        });
        m.put("token", session.getToken());
        return m;
    }

    /** 首次设置访问密码（要求米家已登录、且尚未设置过）。成功后当前会话直接解锁。 */
    @PostMapping("/setup")
    public Map<String, Object> setup(HttpServletRequest request, @RequestBody PasswordRequest req) {
        if (keyStore.isConfigured()) {
            throw ApiException.conflict("访问密码已设置，如需重置请在服务器删除 access.json 后重试");
        }
        if (!sessionHolder.isLoggedIn()) {
            throw ApiException.badRequest("请先登录米家账号，再设置访问密码");
        }
        char[] pw = req == null || req.password() == null ? null : req.password().toCharArray();
        try {
            keyStore.setup(pw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        }
        TokenSession session = obtainSession(request);
        session.setUnlocked(true);
        return gateResult(session);
    }

    /** 校验访问密码并解锁当前会话。 */
    @PostMapping("/unlock")
    public Map<String, Object> unlock(HttpServletRequest request, @RequestBody PasswordRequest req) {
        if (!keyStore.isConfigured()) {
            throw ApiException.badRequest("尚未设置访问密码");
        }
        char[] pw = req == null || req.password() == null ? null : req.password().toCharArray();
        if (!keyStore.verify(pw)) {
            throw ApiException.unauthorized("访问密码错误");
        }
        TokenSession session = obtainSession(request);
        session.setUnlocked(true);
        return gateResult(session);
    }

    /** 主动锁定（顶栏“锁定”）：清除本会话解锁态，下次进入需重新输入密码。 */
    @PostMapping("/lock")
    public Map<String, Object> lock(HttpServletRequest request) {
        TokenSession session = obtainSession(request);
        session.setUnlocked(false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", keyStore.isConfigured());
        m.put("unlocked", false);
        m.put("token", session.getToken());
        return m;
    }

    /**
     * 凭访问密码换取“一键解锁链接”的令牌（不改变当前会话解锁态）。
     * 返回 {@code key} 与相对路径 {@code /unlock?key=...}；旧版记录会在此刻补存令牌哈希。
     */
    @PostMapping("/unlock-link")
    public Map<String, Object> unlockLink(@RequestBody PasswordRequest req) {
        if (!keyStore.isConfigured()) {
            throw ApiException.badRequest("尚未设置访问密码");
        }
        char[] pw = req == null || req.password() == null ? null : req.password().toCharArray();
        String token;
        try {
            token = keyStore.regenerateUnlockToken(pw);
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", token);
        m.put("path", "/unlock?key=" + token);
        return m;
    }

    /** 取请求携带的令牌会话；没有（或令牌已失效）则当场签发一条新会话，保证登录/解锁流程可继续。 */
    private TokenSession obtainSession(HttpServletRequest request) {
        TokenSession session = TokenSessionFilter.currentSession(request);
        return session != null ? session : tokenSessions.create();
    }

    private static Map<String, Object> gateResult(TokenSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", true);
        m.put("unlocked", session.isUnlocked());
        m.put("token", session.getToken());
        return m;
    }

    public static boolean isUnlocked(TokenSession session) {
        return session != null && session.isUnlocked();
    }
}
