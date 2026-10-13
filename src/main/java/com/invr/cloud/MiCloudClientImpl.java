package com.invr.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invr.util.CryptoUtils;
import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Matcher;

/**
 * 米家账号登录实现，协议事实对应 go2rtc pkg/xiaomi/cloud.go（MIT）。
 *
 * <p>流程：serviceLogin 取签名参数 → serviceLoginAuth2（MD5 密码）→
 * 可选图形验证码 / 短信邮箱 2FA → 手动跟随 location 重定向链 finishAuth，
 * 收集 userId/cUserId/serviceToken/passToken 与 ssecurity。
 */
public class MiCloudClientImpl implements MiCloudClient {

    private static final Logger log = LoggerFactory.getLogger(MiCloudClientImpl.class);

    private static final String JSON_PREFIX = "&&&START&&&";
    private static final int MAX_REDIRECTS = 10;
    private static final int MAX_CAPTCHA_RETRIES = 3;
    private static final String LOCALE = "en_US";
    private static final java.util.regex.Pattern RESULT_CHECK_PATTERN =
            java.util.regex.Pattern.compile(
                    "https://account\\.xiaomi\\.com/identity/result/check\\?[^\"'\\s]+");
    private static final java.util.regex.Pattern AUTH_END_PATTERN =
            java.util.regex.Pattern.compile(
                    "https://account\\.xiaomi\\.com/[^\"'\\s]*(?:serviceLoginAuth2/end|identity/result/check)[^\"'\\s]*");

    private final ObjectMapper mapper = new ObjectMapper();
    private final MemoryCookieJar cookieJar = new MemoryCookieJar();
    private final OkHttpClient http;
    /**
     * identity 二次验证系列接口必须跟随 302（go2rtc 使用 http.Client 默认行为）；
     * 与主 client 共享 CookieJar（identity_session 自动收集）。
     */
    private final OkHttpClient identityHttp;

    public MiCloudClientImpl() {
        this.http = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .build();
        this.identityHttp = this.http.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    @Override
    public LoginSession login(String username, String password, String region, LoginInteraction interaction) {
        Objects.requireNonNull(interaction, "登录可能触发验证码/二次验证，必须提供 interaction");
        cookieJar.clear();
        seedDeviceId();
        try {
            // 1. serviceLogin：取 qs / _sign / sid / callback
            JsonNode pre = getJson(MiCloudConfig.SERVICE_LOGIN);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("_json", "true");
            fields.put("hash", CryptoUtils.md5HexUpper(password));
            fields.put("sid", text(pre, "sid"));
            fields.put("callback", text(pre, "callback"));
            fields.put("_sign", text(pre, "_sign"));
            fields.put("qs", text(pre, "qs"));
            fields.put("user", username);

            // 2. serviceLoginAuth2（可能多轮图形验证码）
            JsonNode auth = postForm(MiCloudConfig.AUTH2_URL, toFormBody(fields));
            int captchaRound = 0;
            while (!str(auth, "captchaURL").isEmpty() && captchaRound++ < MAX_CAPTCHA_RETRIES) {
                String captchaUrl = str(auth, "captchaURL");
                log.info("登录需要图形验证码：{}", captchaUrl);
                byte[] image = getBytes(MiCloudConfig.ACCOUNT_BASE + captchaUrl);
                String captcha = interaction.askCaptcha(image);
                if (blank(captcha)) {
                    throw new MiCloudException("未输入图形验证码");
                }
                fields.put("captCode", captcha.trim());
                auth = postForm(MiCloudConfig.AUTH2_URL, toFormBody(fields));
            }
            if (!str(auth, "captchaURL").isEmpty()) {
                throw new MiCloudException("图形验证码连续错误次数过多");
            }

            // 3. 可选短信/邮箱二次验证
            String notificationUrl = str(auth, "notificationUrl");
            if (!notificationUrl.isEmpty()) {
                String location = doTwoFactor(notificationUrl, interaction);
                return finishAuth(location, str(auth, "ssecurity"), region);
            }

            // 4. 直接成功
            String location = str(auth, "location");
            if (location.isEmpty()) {
                throw new MiCloudException("登录失败：" + loginError(auth));
            }
            return finishAuth(location, str(auth, "ssecurity"), region);
        } catch (IOException e) {
            throw new MiCloudException("登录网络异常：" + e.getMessage(), e);
        }
    }

    @Override
    public LoginSession loginWithToken(String userId, String passToken, String region) {
        cookieJar.clear();
        HttpUrl loginUrl = Objects.requireNonNull(HttpUrl.parse(MiCloudConfig.SERVICE_LOGIN));
        cookieJar.setCookie(loginUrl, Cookie.parse(loginUrl,
                "userId=" + userId + "; Path=/; Domain=.xiaomi.com"));
        cookieJar.setCookie(loginUrl, Cookie.parse(loginUrl,
                "passToken=" + passToken + "; Path=/; Domain=.xiaomi.com"));
        try {
            JsonNode s = getJson(MiCloudConfig.SERVICE_LOGIN);
            String location = str(s, "location");
            String ssecurity = str(s, "ssecurity");
            if (location.isEmpty()) {
                throw new MiCloudException("passToken 恢复会话失败：" + loginError(s));
            }
            return finishAuth(location, ssecurity, region);
        } catch (IOException e) {
            throw new MiCloudException("token 恢复网络异常：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 二次验证（2FA）
    // ------------------------------------------------------------------

    /**
     * 二次验证 v2（context 驱动，参考 Xiaomi-cloud-tokens-extractor 的新版实现）：
     * 打开 authStart 建会话 → /identity/list?sid&context → POST send{Phone|Email}Ticket
     * → 用户输入 → POST verify{Phone|Email} → 沿 result/check 解析到 serviceLoginAuth2/end。
     * 返回 Auth2/end（或其之后）的 URL，交由 finishAuth 取 ssecurity 与 serviceToken。
     */
    private String doTwoFactor(String notificationUrl, LoginInteraction interaction) throws IOException {
        HttpUrl notif = HttpUrl.get(notificationUrl);

        // 1. 打开 notificationUrl（authStart，跟随重定向），建立 identity 会话 Cookie
        try (Response ignored = identityHttp.newCall(
                new Request.Builder().url(notificationUrl).get().build()).execute()) {
            if (ignored.body() != null) {
                ignored.body().string();
            }
        }

        // 2. context 来自 notificationUrl 的 query
        String context = notif.queryParameter("context");
        if (blank(context)) {
            throw new MiCloudException(
                    "notificationUrl 缺少 context 参数，无法进行 v2 二次验证：" + notif.encodedPath());
        }

        // 3. GET /identity/list?sid&context&_locale
        String listUrl = HttpUrl.get(MiCloudConfig.ACCOUNT_BASE + "/identity/list").newBuilder()
                .addQueryParameter("sid", MiCloudConfig.SID)
                .addQueryParameter("context", context)
                .addQueryParameter("_locale", LOCALE)
                .build().toString();
        JsonNode list = getJson(identityHttp, listUrl);
        int flag = list.path("flag").asInt(list.path("option").asInt(0));
        int options = 0;
        JsonNode optionsNode = list.path("options");
        if (optionsNode.isArray()) {
            for (JsonNode o : optionsNode) {
                options |= o.asInt(0);
            }
        }
        int selectable = options != 0 ? options : flag;
        String kind;
        int usedFlag;
        if ((selectable & 4) != 0) {
            kind = "Phone";
            usedFlag = 4;
        } else if ((selectable & 8) != 0) {
            kind = "Email";
            usedFlag = 8;
        } else {
            throw new MiCloudException("账号没有可用的二次验证方式：" + list);
        }
        String ick = cookieJar.cookieValue("ick");
        if (ick == null) {
            ick = "";
        }

        // 4. POST send{Kind}Ticket（query 带 sid/context/mask，form 带 retry/icode/_json/ick）
        String sendUrl = HttpUrl.get(MiCloudConfig.ACCOUNT_BASE + "/identity/auth/send" + kind + "Ticket").newBuilder()
                .addQueryParameter("_dc", String.valueOf(System.currentTimeMillis()))
                .addQueryParameter("sid", MiCloudConfig.SID)
                .addQueryParameter("context", context)
                .addQueryParameter("mask", "0")
                .addQueryParameter("_locale", LOCALE)
                .build().toString();
        FormBody sendForm = new FormBody.Builder()
                .add("retry", "0")
                .add("icode", "")
                .add("_json", "true")
                .add("ick", ick)
                .build();
        JsonNode sent = postForm(identityHttp, sendUrl, sendForm);
        if (!str(sent, "captchaURL").isEmpty()) {
            throw new MiCloudException("发送二次验证码前要求图形验证码（验证码+2FA 组合暂未支持）：" + sent);
        }
        if (sent.path("code").asInt(-1) != 0) {
            throw new MiCloudException("发送二次验证码失败：" + sent);
        }

        // 5. 用户输入验证码
        String ticket = interaction.askVerifyCode(kind, "");
        if (blank(ticket)) {
            throw new MiCloudException("未输入二次验证码");
        }

        // 6. POST verify{Kind}（不自动跟随，手动解析收尾链）
        String verifyUrl = HttpUrl.get(MiCloudConfig.ACCOUNT_BASE + "/identity/auth/verify" + kind).newBuilder()
                .addQueryParameter("_flag", String.valueOf(usedFlag))
                .addQueryParameter("_json", "true")
                .addQueryParameter("sid", MiCloudConfig.SID)
                .addQueryParameter("context", context)
                .addQueryParameter("mask", "0")
                .addQueryParameter("_locale", LOCALE)
                .build().toString();
        FormBody verifyForm = new FormBody.Builder()
                .add("_flag", String.valueOf(usedFlag))
                .add("ticket", ticket.trim())
                .add("trust", "false")
                .add("_json", "true")
                .add("ick", ick)
                .build();

        String pending = extractVerifyLocation(verifyUrl, verifyForm, context);
        return resolveToAuthEnd(pending, context);
    }

    /**
     * verify 响应可能是 JSON（含 location）、302（Location 头）或 HTML（内嵌 result/check），
     * 逐种方式提取下一跳地址。
     */
    private String extractVerifyLocation(String verifyUrl, FormBody verifyForm, String context)
            throws IOException {
        Request request = new Request.Builder().url(verifyUrl).post(verifyForm).build();
        try (Response resp = http.newCall(request).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            String text = body;
            if (text.startsWith(JSON_PREFIX)) {
                text = text.substring(JSON_PREFIX.length());
            }
            text = text.trim();

            if (text.startsWith("{")) {
                try {
                    JsonNode j = mapper.readTree(text);
                    String loc = str(j, "location");
                    if (!loc.isEmpty()) {
                        return loc;
                    }
                    if (j.path("code").asInt(-1) != 0) {
                        throw new MiCloudException("二次验证码校验失败：" + text);
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignore) {
                    // 非 JSON，走下面的头/正文兜底
                }
            }
            String headerLoc = resp.header("Location");
            if (!blank(headerLoc)) {
                return HttpUrl.get(verifyUrl).resolve(headerLoc).toString();
            }
            Matcher m = RESULT_CHECK_PATTERN.matcher(text);
            if (m.find()) {
                return m.group(0);
            }
            // 兜底：标准 result/check
            return resultCheckUrl(context);
        }
    }

    /**
     * 沿 identity 收尾重定向链（不自动跟随）走到 serviceLoginAuth2/end。
     */
    private String resolveToAuthEnd(String start, String context) throws IOException {
        String current = start;
        java.util.Set<String> visited = new java.util.HashSet<>();
        for (int i = 0; i < 8 && current != null; i++) {
            if (current.contains("serviceLoginAuth2/end")) {
                return current;
            }
            if (!current.contains("/identity/")) {
                // 已离开 identity 域（可能直接是 STS 等），交给 finishAuth
                return current;
            }
            if (!visited.add(current)) {
                // 兜底 result/check 防止自环
                current = resultCheckUrl(context);
                if (!visited.add(current)) {
                    break;
                }
            }
            Request request = new Request.Builder().url(current).get().build();
            try (Response resp = http.newCall(request).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                String loc = resp.header("Location");
                if (!blank(loc)) {
                    current = HttpUrl.get(current).resolve(loc).toString();
                    continue;
                }
                Matcher m = AUTH_END_PATTERN.matcher(body);
                if (m.find()) {
                    current = m.group(0);
                    continue;
                }
                current = resultCheckUrl(context);
            }
        }
        throw new MiCloudException("无法解析二次验证收尾地址（未到达 serviceLoginAuth2/end）");
    }

    private String resultCheckUrl(String context) {
        return HttpUrl.get(MiCloudConfig.ACCOUNT_BASE + "/identity/result/check").newBuilder()
                .addQueryParameter("sid", MiCloudConfig.SID)
                .addQueryParameter("context", context)
                .addQueryParameter("_locale", LOCALE)
                .build().toString();
    }

    private static String pathOf(String url) {
        try {
            return HttpUrl.get(url).encodedPath();
        } catch (Exception e) {
            return url;
        }
    }

    // ------------------------------------------------------------------
    // finishAuth：跟随重定向链收集凭据
    // ------------------------------------------------------------------

    private LoginSession finishAuth(String location, String ssecurity, String region) throws IOException {
        String url = location;
        int tipsRetry = 0;
        for (int i = 0; i < MAX_REDIRECTS && url != null; i++) {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = http.newCall(request).execute()) {
                // ssecurity 也可能出现在重定向响应头 Extension-Pragma
                String ext = response.header("Extension-Pragma");
                if (!blank(ext)) {
                    try {
                        JsonNode extNode = mapper.readTree(ext);
                        String extSsec = str(extNode, "ssecurity");
                        if (!extSsec.isEmpty()) {
                            ssecurity = extSsec;
                        }
                    } catch (Exception ignore) {
                        // Extension-Pragma 不是 JSON 时忽略
                    }
                }
                String respBody = response.body() != null ? response.body().string() : "";
                int sc = response.code();
                if (sc >= 300 && sc < 400) {
                    String next = response.header("Location");
                    url = next == null ? null : HttpUrl.get(url).resolve(next).toString();
                    continue;
                }
                // 个别账号首次在 Auth2/end 返回 200 "Tips" 页，需对同一 URL 再请求一次才出 302
                if (sc == 200 && tipsRetry++ == 0 && respBody.contains("Xiaomi Account - Tips")) {
                    continue;
                }
                break;
            }
        }

        String userId = cookieJar.cookieValue("userId");
        String cUserId = cookieJar.cookieValue("cUserId");
        String serviceToken = cookieJar.cookieValue("serviceToken");
        String passToken = cookieJar.cookieValue("passToken");
        if (blank(serviceToken)) {
            throw new MiCloudException("finishAuth 未获取到 serviceToken，登录未完成");
        }
        if (blank(ssecurity)) {
            throw new MiCloudException("finishAuth 未获取到 ssecurity，无法签名业务 API");
        }
        return new LoginSession(userId, cUserId, passToken, serviceToken, ssecurity,
                region == null || region.isBlank() ? "cn" : region);
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    private void seedDeviceId() {
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Random random = new Random();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(MiCloudConfig.ACCOUNT_BASE));
        cookieJar.setCookie(url, Cookie.parse(url,
                "deviceId=" + sb + "; Path=/; Domain=.xiaomi.com"));
    }

    private JsonNode getJson(String url) throws IOException {
        return getJson(http, url);
    }

    private JsonNode getJson(OkHttpClient client, String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            return parseBody(body, url, response.code());
        }
    }

    private byte[] getBytes(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new MiCloudException("下载资源失败 HTTP " + response.code() + "：" + url);
            }
            return response.body().bytes();
        }
    }

    private JsonNode postForm(String url, FormBody form) throws IOException {
        return postForm(http, url, form);
    }

    private JsonNode postForm(OkHttpClient client, String url, FormBody form) throws IOException {
        Request request = new Request.Builder().url(url).post(form).build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (body.isBlank()) {
                throw new MiCloudException("HTTP " + response.code() + " 空响应：" + url);
            }
            return parseBody(body, url, response.code());
        }
    }

    private JsonNode parseBody(String body, String url, int httpCode) throws IOException {
        String text = body;
        if (text.startsWith(JSON_PREFIX)) {
            text = text.substring(JSON_PREFIX.length());
        }
        text = text.trim();
        if (text.isEmpty()) {
            throw new MiCloudException("米家接口返回空体 HTTP " + httpCode + "：" + pathOf(url));
        }
        try {
            return mapper.readTree(text);
        } catch (IOException e) {
            throw new MiCloudException("米家接口返回非 JSON HTTP " + httpCode + "：" + pathOf(url)
                    + "，内容前 200 字符=" + text.substring(0, Math.min(200, text.length())), e);
        }
    }

    private static FormBody toFormBody(Map<String, String> fields) {
        FormBody.Builder builder = new FormBody.Builder();
        fields.forEach(builder::add);
        return builder.build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String str(JsonNode node, String field) {
        return text(node, field);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String loginError(JsonNode auth) {
        String desc = str(auth, "description");
        if (desc.isEmpty()) {
            desc = str(auth, "desc");
        }
        String code = str(auth, "code");
        return "code=" + code + (desc.isEmpty() ? "" : " " + desc);
    }
}
