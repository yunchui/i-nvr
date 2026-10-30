package com.invr.web.api;

import com.invr.model.ShareBoard;
import com.invr.web.service.ShareBoardService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 共享看板管理接口（{@code /api/boards/**}，由 AccessInterceptor 强制门禁保护）。
 *
 * <p>提供看板的增删改查、启停、共享令牌与 Webhook 密钥的独立轮换。响应直接序列化
 * {@link ShareBoard} 公共字段；免鉴权只读端点（{@code /share/{token}/board}）在展示链路
 * 控制器中另行提供，不在本类。</p>
 */
@RestController
@RequestMapping("/api/boards")
public class ShareBoardController {

    private final ShareBoardService service;

    public ShareBoardController(ShareBoardService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<ShareBoard> boards = service.list();
        return Map.of("boards", boards, "count", boards.size());
    }

    @GetMapping("/{id}")
    public ShareBoard get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    public ShareBoard create(@RequestBody BoardRequest req) {
        return service.create(req.name(), req.layout(), req.cameras(), req.expireAt());
    }

    @PutMapping("/{id}")
    public ShareBoard update(@PathVariable String id, @RequestBody BoardRequest req) {
        return service.update(id, req.name(), req.layout(), req.cameras(), req.expireAt());
    }

    @PostMapping("/{id}/enabled")
    public ShareBoard setEnabled(@PathVariable String id, @RequestBody EnabledRequest req) {
        return service.setEnabled(id, req.enabled());
    }

    @PostMapping("/{id}/rotate-token")
    public ShareBoard rotateToken(@PathVariable String id) {
        return service.rotateToken(id);
    }

    @PostMapping("/{id}/rotate-secret")
    public ShareBoard rotateSecret(@PathVariable String id) {
        return service.rotateSecret(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("deleted", id);
    }

    /** 新建/编辑请求体。 */
    public record BoardRequest(String name, String layout, List<String> cameras, Long expireAt) {
    }

    /** 启停请求体。 */
    public record EnabledRequest(boolean enabled) {
    }
}
