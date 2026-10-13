package com.invr.web.service;

import com.invr.cloud.LoginInteraction;
import com.invr.cloud.LoginSession;
import com.invr.cloud.MiCloudClient;
import com.invr.cloud.MiCloudClientImpl;
import com.invr.cloud.MiCloudException;
import com.invr.web.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 米家账号登录的 Web 分步状态机。
 *
 * <p>CLI 中登录是一次阻塞调用、验证码/2FA 通过同步回调提问；Web 是多请求异步交互，
 * 故让登录在后台线程运行，{@link LoginInteraction} 的提问阻塞在一个 {@link CompletableFuture}
 * 上，由前端后续的 HTTP 请求（提交图形验证码 / 短信邮箱验证码）唤醒。</p>
 *
 * <pre>
 *   POST /api/login/start                 → STARTING → NEED_CAPTCHA / NEED_2FA / DONE / ERROR
 *   GET  /api/login/{token}/captcha.jpg   → 图形验证码图片
 *   POST /api/login/{token}/captcha       → NEED_CAPTCHA(再错一轮) / NEED_2FA / DONE / ERROR
 *   POST /api/login/{token}/verify        → DONE / ERROR
 * </pre>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public static final String STARTING = "STARTING";
    public static final String NEED_CAPTCHA = "NEED_CAPTCHA";
    public static final String NEED_2FA = "NEED_2FA";
    public static final String SUBMITTING = "SUBMITTING";
    public static final String DONE = "DONE";
    public static final String ERROR = "ERROR";

    private static final long ANSWER_TIMEOUT_MIN = 10;
    private static final long ATTEMPT_TTL_MS = 15 * 60_000L;

    private final SessionHolder sessionHolder;
    private final Map<String, LoginAttempt> attempts = new ConcurrentHashMap<>();

    public AuthService(SessionHolder sessionHolder) {
        this.sessionHolder = sessionHolder;
    }

    /** 发起登录，阻塞到首次需要交互（图形码/2FA）或直接出结果。 */
    public LoginAttempt start(String username, String password, String region) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw ApiException.badRequest("账号和密码不能为空");
        }
        cleanupExpired();
        String token = UUID.randomUUID().toString().replace("-", "");
        LoginAttempt attempt = new LoginAttempt(token, username.trim(), password,
                region == null || region.isBlank() ? "cn" : region.trim());
        attempts.put(token, attempt);

        MiCloudClient client = new MiCloudClientImpl();
        Thread worker = new Thread(() -> {
            try {
                LoginSession session = client.login(
                        attempt.username, attempt.password, attempt.region, attempt.interaction);
                // 必须先把会话落入 holder（内存 + 加密落盘），再把阶段置为 DONE 并通知。
                // 否则前端收到 DONE 后立刻查询 /api/session 可能早于 accept，读到“未登录”，
                // 从而错误地停留在登录页。
                sessionHolder.accept(session);
                synchronized (attempt) {
                    attempt.session = session;
                    attempt.stage = DONE;
                    attempt.notifyAll();
                }
                log.info("Web 登录完成 userId={} region={}", session.userId(), session.region());
            } catch (Throwable t) {
                synchronized (attempt) {
                    attempt.error = t.getMessage() == null ? t.toString() : t.getMessage();
                    attempt.stage = ERROR;
                    attempt.notifyAll();
                }
                log.warn("Web 登录失败：{}", t.getMessage());
            }
        }, "mi-login-" + token.substring(0, 8));
        worker.setDaemon(true);
        worker.start();

        awaitStage(attempt, STARTING, 30_000);
        return attempt;
    }

    /** 图形验证码图片（JPEG）。 */
    public byte[] captchaImage(String token) {
        LoginAttempt a = require(token);
        synchronized (a) {
            if (!NEED_CAPTCHA.equals(a.stage) || a.captchaImage == null) {
                throw ApiException.badRequest("当前登录流程不等待图形验证码");
            }
            return a.captchaImage;
        }
    }

    /** 提交图形验证码，返回推进后的阶段。 */
    public LoginAttempt submitCaptcha(String token, String code) {
        LoginAttempt a = require(token);
        CompletableFuture<String> future;
        synchronized (a) {
            if (!NEED_CAPTCHA.equals(a.stage) || a.captchaAnswer == null) {
                throw ApiException.badRequest("当前登录流程不等待图形验证码");
            }
            future = a.captchaAnswer;
            a.stage = SUBMITTING;
            a.notifyAll();
        }
        future.complete(code == null ? "" : code.trim());
        awaitStage(a, SUBMITTING, 30_000);
        return a;
    }

    /** 提交短信/邮箱验证码，返回推进后的阶段。 */
    public LoginAttempt submitVerify(String token, String code) {
        LoginAttempt a = require(token);
        CompletableFuture<String> future;
        synchronized (a) {
            if (!NEED_2FA.equals(a.stage) || a.verifyAnswer == null) {
                throw ApiException.badRequest("当前登录流程不等待二次验证码");
            }
            future = a.verifyAnswer;
            a.stage = SUBMITTING;
            a.notifyAll();
        }
        future.complete(code == null ? "" : code.trim());
        awaitStage(a, SUBMITTING, 30_000);
        return a;
    }

    public void logout() {
        sessionHolder.clear();
    }

    private LoginAttempt require(String token) {
        cleanupExpired();
        LoginAttempt a = attempts.get(token);
        if (a == null) {
            throw ApiException.badRequest("登录流程已失效，请重新发起登录");
        }
        return a;
    }

    private void awaitStage(LoginAttempt a, String waitStage, long timeoutMs) {
        synchronized (a) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (waitStage.equals(a.stage)) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    throw new MiCloudException("登录服务响应超时");
                }
                try {
                    a.wait(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MiCloudException("登录等待被中断");
                }
            }
        }
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        attempts.values().removeIf(a -> now - a.createdAt > ATTEMPT_TTL_MS);
    }

    /** 一次登录尝试的状态与跨请求交互桥。 */
    public static final class LoginAttempt {
        final String token;
        final String username;
        final String password;
        final String region;
        final long createdAt = System.currentTimeMillis();

        volatile String stage = STARTING;
        volatile byte[] captchaImage;
        volatile String verifyKind = "";
        volatile String verifyTarget = "";
        volatile LoginSession session;
        volatile String error;

        volatile CompletableFuture<String> captchaAnswer;
        volatile CompletableFuture<String> verifyAnswer;

        LoginAttempt(String token, String username, String password, String region) {
            this.token = token;
            this.username = username;
            this.password = password;
            this.region = region;
        }

        final LoginInteraction interaction = new LoginInteraction() {
            @Override
            public String askCaptcha(byte[] image) {
                CompletableFuture<String> f = new CompletableFuture<>();
                synchronized (LoginAttempt.this) {
                    captchaImage = image;
                    captchaAnswer = f;
                    stage = NEED_CAPTCHA;
                    LoginAttempt.this.notifyAll();
                }
                return block(f, "图形验证码");
            }

            @Override
            public String askVerifyCode(String kind, String maskedTarget) {
                CompletableFuture<String> f = new CompletableFuture<>();
                synchronized (LoginAttempt.this) {
                    verifyKind = kind;
                    verifyTarget = maskedTarget == null ? "" : maskedTarget;
                    verifyAnswer = f;
                    stage = NEED_2FA;
                    LoginAttempt.this.notifyAll();
                }
                return block(f, "二次验证码");
            }
        };

        private String block(CompletableFuture<String> f, String what) {
            try {
                return f.get(ANSWER_TIMEOUT_MIN, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                throw new MiCloudException("等待" + what + "超时");
            } catch (java.util.concurrent.ExecutionException e) {
                throw new MiCloudException(what + "提交异常：" + e.getCause().getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MiCloudException("等待" + what + "被中断");
            }
        }

        public String getToken() {
            return token;
        }

        public String getStage() {
            return stage;
        }

        public String getVerifyKind() {
            return verifyKind;
        }

        public String getVerifyTarget() {
            return verifyTarget;
        }

        public LoginSession getSession() {
            return session;
        }

        public String getError() {
            return error;
        }
    }
}
