package com.invr.web.api;

import com.invr.store.AccessKeyStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 浏览器“一键解锁链接”入口：{@code GET /unlock?key=<令牌>}。
 *
 * <p>令牌由访问密码经 {@link AccessKeyStore#buildUnlockToken(char[])} 派生（见锁页
 * “生成一键解锁链接”）。校验通过即在当前浏览器会话写入解锁标记并 302 跳回首页，
 * 前端据会话状态直接进入系统；校验失败跳回首页并带 {@code unlock_error=1}，由锁页提示。</p>
 *
 * <p>该路径为顶层页面跳转，不在 {@code AccessInterceptor} 的 /api、/hls 拦截范围内。</p>
 */
@Controller
public class UnlockController {

    private final AccessKeyStore keyStore;

    public UnlockController(AccessKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    @GetMapping("/unlock")
    public String unlock(@RequestParam(name = "key", required = false) String key,
                         HttpServletRequest request) {
        if (key != null && keyStore.verifyUnlockToken(key)) {
            HttpSession session = request.getSession(true);
            session.setAttribute(AccessController.SESSION_UNLOCKED, Boolean.TRUE);
            return "redirect:/?unlocked=1";
        }
        return "redirect:/?unlock_error=1";
    }
}
