package com.invr.web.config;

import com.invr.store.ApiKeyStore;
import com.invr.web.api.AccessController;
import com.invr.web.service.TokenSessionManager.TokenSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 访问密码门禁拦截器：对受保护的 API 与 HLS 资源强制校验当前请求的令牌会话是否已解锁。
 *
 * <p>未解锁（含“尚未设置访问密码”阶段——此时不可能有任何会话被解锁；以及未携带令牌/令牌
 * 过期）一律返回 401 + {@code ACCESS_LOCKED}，前端据此回到“设置/输入访问密码”门禁页。
 * 白名单（门禁自身、米家登录分步接口、会话探测、登出、静态资源）在 {@link WebSecurityConfig}
 * 中放行。</p>
 *
 * <p><b>API 密钥通道</b>：外部系统无法走网页解锁流程，对显式登记的开放接口（见
 * {@link #API_KEY_ALLOWED_PATTERNS}，当前仅 GET 截帧）允许用请求头
 * {@code X-API-Key: <密钥>} 代替门禁令牌；密钥经 {@link ApiKeyStore} 做摘要恒定时间校验。
 * 白名单之外的任何接口（含全部设置/管理类接口）一律不接受 API 密钥，携带错误密钥时返回
 * 401 + {@code API_KEY_INVALID}，与“未携带密钥”的 ACCESS_LOCKED 区分，便于调用方排障。</p>
 */
public class AccessInterceptor implements HandlerInterceptor {

    /** API 密钥请求头名称（Servlet 头名大小写不敏感）。 */
    public static final String API_KEY_HEADER = "X-API-Key";

    /**
     * 允许凭 API 密钥访问的接口白名单（Ant 风格，仅 GET）。新增开放接口时在此显式登记，
     * 避免一把密钥意外获得全部管理接口权限。
     */
    private static final List<String> API_KEY_ALLOWED_PATTERNS = List.of(
            "/api/snapshot/**"
    );

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final byte[] LOCKED_BODY =
            ("{\"error\":\"访问未解锁，请先输入访问密码\",\"code\":\"ACCESS_LOCKED\"}")
                    .getBytes(StandardCharsets.UTF_8);

    private static final byte[] API_KEY_DENY_BODY =
            ("{\"error\":\"API 密钥无效或无权访问该接口\",\"code\":\"API_KEY_INVALID\"}")
                    .getBytes(StandardCharsets.UTF_8);

    private final ApiKeyStore apiKeyStore;

    public AccessInterceptor(ApiKeyStore apiKeyStore) {
        this.apiKeyStore = apiKeyStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        TokenSession session = TokenSessionFilter.currentSession(request);
        if (AccessController.isUnlocked(session)) {
            return true;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            if (isApiKeyAllowed(request) && apiKeyStore.verify(apiKey.trim())) {
                return true;
            }
            writeDeny(response, API_KEY_DENY_BODY);
            return false;
        }

        writeDeny(response, LOCKED_BODY);
        return false;
    }

    /** 仅白名单路径且 GET 方法可用 API 密钥；管理/写接口即使密钥正确也不放行。 */
    private static boolean isApiKeyAllowed(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        for (String pattern : API_KEY_ALLOWED_PATTERNS) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private static void writeDeny(HttpServletResponse response, byte[] body) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
