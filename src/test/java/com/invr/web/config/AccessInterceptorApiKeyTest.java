package com.invr.web.config;

import com.invr.store.ApiKeyStore;
import com.invr.web.service.TokenSessionManager;
import com.invr.web.service.TokenSessionManager.TokenSession;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AccessInterceptor} 的 API 密钥通道与门禁兜底行为（不启动 Spring 容器）。
 * 白名单当前仅 GET /api/snapshot/**；管理接口即使密钥正确也必须拒绝。
 */
class AccessInterceptorApiKeyTest {

    private static final String GOOD_KEY = "invr_test-key-0123456789abcdef";
    private static final String BAD_KEY = "invr_wrong-key";

    private final TokenSessionManager tokenManager = new TokenSessionManager();

    private ApiKeyStore keyStore(boolean configured) {
        ApiKeyStore store = mock(ApiKeyStore.class);
        if (configured) {
            when(store.verify(eq(GOOD_KEY))).thenReturn(true);
        }
        // 未配置或密钥不匹配时，Mockito 对 boolean 的默认返回值即 false，无需额外打桩
        return store;
    }

    private HttpServletRequest request(String method, String uri, String contextPath,
                                       String apiKey, TokenSession session) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getContextPath()).thenReturn(contextPath);
        when(req.getHeader(AccessInterceptor.API_KEY_HEADER)).thenReturn(apiKey);
        when(req.getAttribute(TokenSessionFilter.SESSION_ATTRIBUTE)).thenReturn(session);
        return req;
    }

    private static class CapturingResponse {
        final HttpServletResponse servlet = mock(HttpServletResponse.class);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final ServletOutputStream out = new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener writeListener) { }
            @Override public void write(int b) { bytes.write(b); }
        };

        CapturingResponse() throws Exception {
            when(servlet.getOutputStream()).thenReturn(out);
        }

        int status() {
            var captor = org.mockito.ArgumentCaptor.forClass(Integer.class);
            verify(servlet).setStatus(captor.capture());
            return captor.getValue();
        }

        String body() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void lockedSessionWithoutApiKeyGetsAccessLocked() throws Exception {
        AccessInterceptor interceptor = new AccessInterceptor(keyStore(true));
        CapturingResponse resp = new CapturingResponse();
        HttpServletRequest req = request("GET", "/api/devices", "", null, null);

        assertFalse(interceptor.preHandle(req, resp.servlet, new Object()));
        org.junit.jupiter.api.Assertions.assertEquals(401, resp.status());
        assertTrue(resp.body().contains("ACCESS_LOCKED"));
    }

    @Test
    void validApiKeyOnSnapshotGetPasses() throws Exception {
        AccessInterceptor interceptor = new AccessInterceptor(keyStore(true));
        CapturingResponse resp = new CapturingResponse();
        HttpServletRequest req = request("GET", "/api/snapshot/123456", "", GOOD_KEY, null);

        assertTrue(interceptor.preHandle(req, resp.servlet, new Object()));
        verify(resp.servlet, never()).setStatus(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void validApiKeyStillBlockedOnNonWhitelistedPath() throws Exception {
        AccessInterceptor interceptor = new AccessInterceptor(keyStore(true));
        CapturingResponse resp = new CapturingResponse();
        HttpServletRequest req = request("GET", "/api/settings/apikey", "", GOOD_KEY, null);

        assertFalse(interceptor.preHandle(req, resp.servlet, new Object()));
        org.junit.jupiter.api.Assertions.assertEquals(401, resp.status());
        assertTrue(resp.body().contains("API_KEY_INVALID"));
    }

    @Test
    void apiKeyNotAllowedForPostEvenOnSnapshotPath() throws Exception {
        AccessInterceptor interceptor = new AccessInterceptor(keyStore(true));
        CapturingResponse resp = new CapturingResponse();
        HttpServletRequest req = request("POST", "/api/snapshot/123456", "", GOOD_KEY, null);

        assertFalse(interceptor.preHandle(req, resp.servlet, new Object()));
        org.junit.jupiter.api.Assertions.assertEquals(401, resp.status());
        assertTrue(resp.body().contains("API_KEY_INVALID"));
    }

    @Test
    void wrongApiKeyGetsApiKeyInvalid() throws Exception {
        ApiKeyStore store = keyStore(true);
        AccessInterceptor interceptor = new AccessInterceptor(store);
        CapturingResponse resp = new CapturingResponse();
        HttpServletRequest req = request("GET", "/api/snapshot/123456", "", BAD_KEY, null);

        assertFalse(interceptor.preHandle(req, resp.servlet, new Object()));
        org.junit.jupiter.api.Assertions.assertEquals(401, resp.status());
        assertTrue(resp.body().contains("API_KEY_INVALID"));
        verify(store).verify(BAD_KEY);
    }

    @Test
    void contextPathIsStrippedBeforeWhitelistMatch() throws Exception {
        AccessInterceptor interceptor = new AccessInterceptor(keyStore(true));
        CapturingResponse resp = new CapturingResponse();
        HttpServletRequest req = request("GET", "/app/invr/api/snapshot/123456",
                "/app/invr", GOOD_KEY, null);

        assertTrue(interceptor.preHandle(req, resp.servlet, new Object()));
    }

    @Test
    void unlockedWebSessionBypassesApiKeyCheckOnAnyPath() throws Exception {
        AccessInterceptor interceptor = new AccessInterceptor(keyStore(false));
        CapturingResponse resp = new CapturingResponse();
        TokenSession session = tokenManager.create();
        session.setUnlocked(true);
        HttpServletRequest req = request("GET", "/api/settings", "", null, session);

        assertTrue(interceptor.preHandle(req, resp.servlet, new Object()));
        verify(resp.servlet, never()).setStatus(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void apiKeyWithoutConfiguredKeyIsRejected() throws Exception {
        AccessInterceptor interceptor = new AccessInterceptor(keyStore(false));
        CapturingResponse resp = new CapturingResponse();
        HttpServletRequest req = request("GET", "/api/snapshot/123456", "", GOOD_KEY, null);

        assertFalse(interceptor.preHandle(req, resp.servlet, new Object()));
        org.junit.jupiter.api.Assertions.assertEquals(401, resp.status());
        assertTrue(resp.body().contains("API_KEY_INVALID"));
    }
}
