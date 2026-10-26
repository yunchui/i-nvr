package com.invr.web.config;

import com.invr.web.api.AccessController;
import com.invr.web.service.TokenSessionManager.TokenSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 访问密码门禁拦截器：对受保护的 API 与 HLS 资源强制校验当前请求的令牌会话是否已解锁。
 *
 * <p>未解锁（含“尚未设置访问密码”阶段——此时不可能有任何会话被解锁；以及未携带令牌/令牌
 * 过期）一律返回 401 + {@code ACCESS_LOCKED}，前端据此回到“设置/输入访问密码”门禁页。
 * 白名单（门禁自身、米家登录分步接口、会话探测、登出、静态资源）在 {@link WebSecurityConfig}
 * 中放行。</p>
 */
public class AccessInterceptor implements HandlerInterceptor {

    private static final byte[] DENY_BODY =
            ("{\"error\":\"访问未解锁，请先输入访问密码\",\"code\":\"ACCESS_LOCKED\"}")
                    .getBytes(StandardCharsets.UTF_8);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        TokenSession session = TokenSessionFilter.currentSession(request);
        if (AccessController.isUnlocked(session)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setContentLength(DENY_BODY.length);
        response.getOutputStream().write(DENY_BODY);
        return false;
    }
}
