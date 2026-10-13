package com.invr.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 门禁装配：把 {@link AccessInterceptor} 挂到所有受保护资源上。
 *
 * <p>默认拦截 {@code /api/**} 与 {@code /hls/**}（白名单之外的新接口自动受保护），
 * 放行门禁自身、米家登录分步接口、会话探测与登出；静态页面/脚本不在拦截路径内，
 * 保证门禁页本身始终可加载。HLS 由同源 {@code <video>} 发起，会自动携带 JSESSIONID，
 * 故解锁后可正常播放，未解锁则被 401 拒绝。</p>
 */
@Configuration
public class WebSecurityConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccessInterceptor())
                .addPathPatterns("/api/**", "/hls/**")
                .excludePathPatterns(
                        // 门禁自身：状态查询 / 设置 / 解锁 / 锁定
                        "/api/access/**",
                        // 米家登录分步状态机（含图形验证码图片）
                        "/api/login/**",
                        // 启动时探测米家登录态、登出
                        "/api/session",
                        "/api/logout"
                );
    }
}
