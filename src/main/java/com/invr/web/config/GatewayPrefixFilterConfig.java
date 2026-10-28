package com.invr.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * fnOS 统一网关联调：网关把 {@code /app/{appname}/**} 的请求转发到本应用的 Unix Socket，
 * 请求路径带 {@code /app/{appname}} 前缀；而本应用 Controller 映射在根路径（/api、/hls、静态资源 /**）。
 *
 * <p>零配置：前缀由环境变量 {@code TRIM_APPNAME} 自动推导（fnOS 运行时注入）。
 * 本地开发没有该变量时 Filter 直接放行，纯直连行为不变。</p>
 */
@Configuration
public class GatewayPrefixFilterConfig {

    @Bean
    public FilterRegistrationBean<GatewayPrefixFilter> gatewayPrefixFilter() {
        FilterRegistrationBean<GatewayPrefixFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new GatewayPrefixFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    static class GatewayPrefixFilter extends OncePerRequestFilter {
        private final String prefix;

        GatewayPrefixFilter() {
            String appName = System.getenv("TRIM_APPNAME");
            this.prefix = (appName == null || appName.isBlank()) ? "" : "/app/" + appName.trim();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            if (prefix.isEmpty()) {
                chain.doFilter(request, response);
                return;
            }
            String uri = request.getRequestURI();
            // 访问前缀本身（无尾斜杠）→ 302 补尾斜杠
            if (uri.equals(prefix)) {
                response.setStatus(HttpServletResponse.SC_FOUND);
                response.setHeader("Location", prefix + "/");
                response.getOutputStream().close();
                return;
            }
            if (uri.startsWith(prefix + "/")) {
                String remainder = uri.substring(prefix.length()); // 一定以 / 开头
                chain.doFilter(new StrippedRequest(request, remainder), response);
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * 把 request URI 重写为剥掉网关前缀后的路径。
     * 关键：正确处理 forward/include 的 request attribute，避免 WelcomePage forward 死循环。
     */
    static class StrippedRequest extends HttpServletRequestWrapper {
        private final String path;

        StrippedRequest(HttpServletRequest request, String path) {
            super(request);
            this.path = path;
        }

        @Override
        public String getRequestURI() {
            // forward/include 时 RequestDispatcher 会设置这些 attribute，优先使用它们
            Object forwardUri = getAttribute("jakarta.servlet.forward.request_uri");
            if (forwardUri != null) {
                return (String) forwardUri;
            }
            Object includeUri = getAttribute("jakarta.servlet.include.request_uri");
            if (includeUri != null) {
                return (String) includeUri;
            }
            return path;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = super.getRequestURL();
            String orig = super.getRequestURI();
            int idx = url.indexOf(orig);
            if (idx >= 0) {
                url.delete(idx, idx + orig.length());
                url.insert(idx, path);
            }
            return url;
        }

        @Override
        public String getContextPath() {
            return "";
        }
    }
}
