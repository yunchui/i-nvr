package com.invr.web.api;

import com.invr.store.ApiKeyStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放接口 API 密钥管理（位于 {@code /api/settings/apikey}，仅 Web 门禁会话可访问）。
 *
 * <ul>
 *   <li>{@code GET}：查询配置状态与脱敏掩码；</li>
 *   <li>{@code POST /generate}：生成随机密钥，明文仅本次返回；</li>
 *   <li>{@code PUT}：保存自定义密钥；</li>
 *   <li>{@code DELETE}：删除密钥，关闭密钥访问方式。</li>
 * </ul>
 * 这些管理接口不在 API 密钥白名单内，密钥本身不能用来管理密钥。
 */
@RestController
@RequestMapping("/api/settings/apikey")
public class ApiKeyController {

    private final ApiKeyStore store;

    public ApiKeyController(ApiKeyStore store) {
        this.store = store;
    }

    /** 查询当前密钥状态（不返回明文）。 */
    @GetMapping
    public Map<String, Object> status() {
        return infoMap(store.info(), null);
    }

    /** 生成新的随机密钥；旧密钥立即失效，明文只在本次响应中返回一次。 */
    @PostMapping("/generate")
    public Map<String, Object> generate() {
        String plain = store.generate();
        return infoMap(store.info(), plain);
    }

    /** 保存自定义密钥（替换旧密钥）。 */
    @PutMapping
    public Map<String, Object> save(@RequestBody(required = false) ApiKeyRequest req) {
        if (req == null || req.apiKey() == null || req.apiKey().isBlank()) {
            throw ApiException.badRequest("缺少 apiKey");
        }
        try {
            String plain = store.saveCustom(req.apiKey());
            return infoMap(store.info(), plain);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 删除密钥。 */
    @DeleteMapping
    public Map<String, Object> remove() {
        store.clear();
        return infoMap(store.info(), null);
    }

    private static Map<String, Object> infoMap(ApiKeyStore.Info info, String plaintext) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", info.configured());
        m.put("mask", info.mask());
        m.put("createdAt", info.createdAt());
        // 明文密钥仅生成/保存当次返回，列表/状态接口恒为 null
        if (plaintext != null) {
            m.put("apiKey", plaintext);
        }
        return m;
    }

    public record ApiKeyRequest(String apiKey) {
    }
}
