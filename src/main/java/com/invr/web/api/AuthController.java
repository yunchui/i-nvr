package com.invr.web.api;

import com.invr.web.service.AuthService;
import com.invr.web.service.SessionHolder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 米家账号登录 / 会话 / 登出。登录为分步交互（图形验证码、短信/邮箱 2FA）。
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;
    private final SessionHolder sessionHolder;

    public AuthController(AuthService authService, SessionHolder sessionHolder) {
        this.authService = authService;
        this.sessionHolder = sessionHolder;
    }

    public record LoginRequest(String username, String password, String region) {
    }

    public record CodeRequest(String code) {
    }

    @GetMapping("/session")
    public Map<String, Object> session() {
        Map<String, Object> m = new LinkedHashMap<>();
        var s = sessionHolder.get().orElse(null);
        m.put("loggedIn", s != null);
        if (s != null) {
            m.put("userId", s.userId());
            m.put("region", s.region());
        }
        return m;
    }

    @PostMapping("/login/start")
    public Map<String, Object> start(@RequestBody LoginRequest req) {
        AuthService.LoginAttempt a = authService.start(
                req == null ? null : req.username(),
                req == null ? null : req.password(),
                req == null ? null : req.region());
        return snapshot(a);
    }

    @GetMapping(value = "/login/{token}/captcha", produces = MediaType.IMAGE_JPEG_VALUE)
    public byte[] captcha(@PathVariable String token) {
        return authService.captchaImage(token);
    }

    @PostMapping("/login/{token}/captcha")
    public Map<String, Object> submitCaptcha(@PathVariable String token, @RequestBody CodeRequest req) {
        return snapshot(authService.submitCaptcha(token, req == null ? null : req.code()));
    }

    @PostMapping("/login/{token}/verify")
    public Map<String, Object> submitVerify(@PathVariable String token, @RequestBody CodeRequest req) {
        return snapshot(authService.submitVerify(token, req == null ? null : req.code()));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout() {
        authService.logout();
        return Map.of("loggedIn", false);
    }

    private static Map<String, Object> snapshot(AuthService.LoginAttempt a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", a.getStage());
        m.put("token", a.getToken());
        m.put("verifyKind", a.getVerifyKind() == null ? "" : a.getVerifyKind());
        m.put("verifyTarget", a.getVerifyTarget() == null ? "" : a.getVerifyTarget());
        if (AuthService.DONE.equals(a.getStage()) && a.getSession() != null) {
            m.put("loggedIn", true);
            m.put("userId", a.getSession().userId());
            m.put("region", a.getSession().region());
        } else {
            m.put("loggedIn", false);
        }
        if (AuthService.ERROR.equals(a.getStage())) {
            m.put("error", a.getError());
        }
        return m;
    }
}
