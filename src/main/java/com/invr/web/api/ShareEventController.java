package com.invr.web.api;

import com.invr.web.service.ShareEventService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 共享看板事件入口（免登录，由各自机制授权）。
 *
 * <ul>
 *   <li>{@code POST /share-event}：外部系统 Webhook，请求头 {@code X-Share-Secret} 携带看板密钥，
 *       body {@code {"did":"...","eventType":"..."}}（eventType 可选）；</li>
 *   <li>{@code GET /share/{token}/events}：展示端 SSE 长连接，接收 snapshot/switch/end。</li>
 * </ul>
 */
@RestController
public class ShareEventController {

    private final ShareEventService events;

    public ShareEventController(ShareEventService events) {
        this.events = events;
    }

    @PostMapping("/share-event")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestHeader(value = "X-Share-Secret", required = false) String secret,
            @RequestBody(required = false) Map<String, Object> body) {

        if (secret == null || secret.trim().isEmpty()) {
            return error(401, "WEBHOOK_UNAUTHORIZED", "缺少请求头 X-Share-Secret");
        }
        if (body == null || body.get("did") == null) {
            return error(400, "BAD_REQUEST", "请求体缺少 did 字段");
        }
        String did = String.valueOf(body.get("did"));

        ShareEventService.TriggerResult result = events.trigger(secret, did);
        return switch (result) {
            case OK -> ResponseEntity.ok(Map.of("accepted", true));
            case UNAUTHORIZED -> error(401, "WEBHOOK_UNAUTHORIZED", "密钥无效");
            case BOARD_UNAVAILABLE -> error(410, "BOARD_UNAVAILABLE", "看板不可用、已停用或已过期");
            case DID_NOT_IN_BOARD -> error(422, "DID_NOT_IN_BOARD", "did 不属于该看板");
        };
    }

    @GetMapping(value = "/share/{token}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String token) {
        SseEmitter emitter = events.subscribe(token);
        if (emitter == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.GONE, "共享看板不可用");
        }
        return emitter;
    }

    private ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        return ResponseEntity.status(status).body(m);
    }
}
