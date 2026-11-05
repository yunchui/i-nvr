package com.invr.web.api;

import com.invr.store.ApiKeyStore;
import com.invr.web.config.AccessInterceptor;
import com.invr.web.service.TokenSessionManager;
import com.invr.web.service.TokenSessionManager.TokenSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ApiKeyController} 的 Web 层独立测试（不启动完整 Spring 容器）：
 * 用 standalone MockMvc 挂上真实 {@link AccessInterceptor} 与真实 {@link ApiKeyStore}，
 * 验证路径映射、请求体反序列化、JSON 响应与门禁拦截的完整 HTTP 语义。
 */
class ApiKeyControllerWebTest {

    @TempDir
    Path tempDir;

    private MockMvc mvc;
    private ApiKeyStore store;
    private final TokenSessionManager tokens = new TokenSessionManager();

    @BeforeEach
    void setUp() {
        store = new ApiKeyStore(tempDir);
        AccessInterceptor interceptor = new AccessInterceptor(store);
        mvc = MockMvcBuilders.standaloneSetup(new ApiKeyController(store))
                .addInterceptors(interceptor)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** standalone 环境下用请求属性模拟 TokenSessionFilter 注入的已解锁会话。 */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder unlocked(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
        TokenSession session = new TokenSessionManager().create();
        session.setUnlocked(true);
        return b.requestAttr(com.invr.web.config.TokenSessionFilter.SESSION_ATTRIBUTE, session);
    }

    @Test
    void lockedSessionWithoutKeyIsRejected() throws Exception {
        mvc.perform(get("/api/settings/apikey"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_LOCKED"));
    }

    @Test
    void statusUnconfiguredThenGenerateReturnsPlaintextOnce() throws Exception {
        mvc.perform(unlocked(get("/api/settings/apikey")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        String body = mvc.perform(unlocked(post("/api/settings/apikey/generate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andExpect(jsonPath("$.mask").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNumber())
                .andReturn().getResponse().getContentAsString();

        // 后续状态查询不再返回明文
        mvc.perform(unlocked(get("/api/settings/apikey")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        // 生成响应中的明文密钥可被落盘摘要校验通过
        String plain = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("apiKey").asText();
        org.junit.jupiter.api.Assertions.assertTrue(store.verify(plain));
    }

    @Test
    void putCustomKeyValidatesAndPersists() throws Exception {
        mvc.perform(unlocked(put("/api/settings/apikey"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        mvc.perform(unlocked(put("/api/settings/apikey"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"my-custom-key-1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.apiKey").value("my-custom-key-1234567890"));
        org.junit.jupiter.api.Assertions.assertTrue(store.verify("my-custom-key-1234567890"));
    }

    @Test
    void deleteClearsKey() throws Exception {
        mvc.perform(unlocked(post("/api/settings/apikey/generate"))).andExpect(status().isOk());
        mvc.perform(unlocked(delete("/api/settings/apikey")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
        org.junit.jupiter.api.Assertions.assertFalse(store.isConfigured());
    }

    @Test
    void apiKeyHeaderCannotReachManagementEndpoint() throws Exception {
        mvc.perform(unlocked(post("/api/settings/apikey/generate"))).andExpect(status().isOk());
        // 白名单不含管理接口：即使密钥正确也拒绝（此处用未解锁会话 + 密钥头模拟外部调用）
        String key = store.generate();
        mvc.perform(get("/api/settings/apikey").header(AccessInterceptor.API_KEY_HEADER, key))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_INVALID"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
