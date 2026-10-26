package com.invr.web.config;

import com.invr.web.service.TokenSessionManager;
import com.invr.web.service.TokenSessionManager.TokenSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 解析门禁令牌，并把对应的 {@link TokenSession} 挂到当前请求上。
 *
 * <p>令牌来源（按优先级）：
 * <ol>
 *   <li>{@code Authorization: Bearer <token>} 头——前端 fetch 统一注入；</li>
 *   <li>{@code __invr_t=<token>} 查询参数——无法加自定义请求头的场景：原生 HLS 的
 *       {@code <video>} 子资源、录像 {@code <video>} 播放与 {@code <a download>} 下载。</li>
 * </ol>
 * 解析不到有效令牌时不设置请求属性，{@link AccessInterceptor} 据此返回 401。</p>
 *
 * <p>本过滤器不依赖、也不创建 {@link jakarta.servlet.http.HttpSession}，因此服务端不再下发
 * 任何 JSESSIONID Cookie，从根上规避跨站 iframe 的第三方 Cookie 限制。</p>
 */
@Component
public class TokenSessionFilter extends OncePerRequestFilter {

    /** 请求属性名：当前请求解析出的 {@link TokenSession}。 */
    public static final String SESSION_ATTRIBUTE = TokenSessionFilter.class.getName() + ".session";
    /** 媒体 / 下载等无法加头的请求通过该查询参数携带令牌。 */
    public static final String QUERY_PARAM = "__invr_t";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenSessionManager manager;

    public TokenSessionFilter(TokenSessionManager manager) {
        this.manager = manager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            TokenSession session = manager.resolve(token);
            if (session != null) {
                request.setAttribute(SESSION_ATTRIBUTE, session);
            }
        }
        filterChain.doFilter(request, response);
    }

    private static String extractToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            String t = auth.substring(BEARER_PREFIX.length()).trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        // 仅查询 URL：媒体标签 / 下载链接无法携带自定义头时使用。对 application/json 的 POST，
        // getParameter 只解析 URL query、不读取请求体，不会影响后续 JSON 反序列化。
        return request.getParameter(QUERY_PARAM);
    }

    /** 取当前请求的令牌会话；未携带令牌或令牌已过期/无效时返回 null。 */
    public static TokenSession currentSession(HttpServletRequest request) {
        Object attr = request.getAttribute(SESSION_ATTRIBUTE);
        return attr instanceof TokenSession s ? s : null;
    }
}
