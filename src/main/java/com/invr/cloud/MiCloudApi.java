package com.invr.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * 米家业务 API 调用封装：签名 → POST → 解密 → code 校验。
 */
public class MiCloudApi {

    private static final Logger log = LoggerFactory.getLogger(MiCloudApi.class);

    private final LoginSession session;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public MiCloudApi(LoginSession session) {
        this.session = session;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * 调用一个业务 API。
     *
     * @param apiURL 以 / 开头的路径，如 /v2/home/device_list_page
     * @param params 参数对象，序列化为 JSON；null 视为 {}
     * @return result 节点
     * @throws MiCloudUnauthorizedException code=401，调用方应 token 恢复后重试
     */
    public JsonNode call(String apiURL, Object params) {
        String dataJson;
        try {
            dataJson = mapper.writeValueAsString(params == null ? java.util.Collections.emptyMap() : params);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new MiCloudException("业务参数序列化失败：" + e.getMessage(), e);
        }

        CloudApiSigner signer = new CloudApiSigner(session.ssecurity());
        CloudApiSigner.SignedRequest signed = signer.sign(apiURL, dataJson);

        Request request = new Request.Builder()
                .url(MiCloudConfig.apiBase(session.region()) + apiURL)
                .header("Cookie", session.cookieString())
                .post(signed.body())
                .build();

        String raw;
        try (Response response = http.newCall(request).execute()) {
            raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful() && raw.isBlank()) {
                throw new MiCloudException("业务 API HTTP " + response.code() + "：" + apiURL);
            }
        } catch (IOException e) {
            throw new MiCloudException("业务 API 网络异常：" + apiURL + " - " + e.getMessage(), e);
        }

        String decrypted = signer.decryptResponse(signed.signedNonce(), raw);
        final JsonNode json;
        try {
            json = mapper.readTree(decrypted);
        } catch (IOException e) {
            throw new MiCloudException("业务 API 响应解析失败：" + apiURL, e);
        }

        int code = json.path("code").asInt(-1);
        if (code == 401) {
            throw new MiCloudUnauthorizedException("会话失效（code=401），需 token 恢复");
        }
        if (code != 0) {
            throw new MiCloudException("业务 API 失败 code=" + code
                    + " message=" + json.path("message").asText("") + " path=" + apiURL);
        }
        return json.path("result");
    }
}
