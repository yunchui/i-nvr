package com.invr.web.api;

import com.invr.model.BoardSlot;
import com.invr.model.CameraDevice;
import com.invr.model.ShareBoard;
import com.invr.web.service.BoardLayouts;
import com.invr.web.service.DeviceCatalog;
import com.invr.web.service.ShareBoardService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 免鉴权共享看板页面入口与只读配置。
 *
 * <ul>
 *   <li>{@code GET /share/{token}}：校验令牌后返回大屏页 share.html；失效返回 410 提示；</li>
 *   <li>{@code GET /share/{token}/board}：返回布局槽位、摄像头与取流/事件路径的最小信息，
 *       不包含任何本看板之外的设备或系统信息。</li>
 * </ul>
 */
@Controller
public class SharePageController {

    private final ShareBoardService boards;
    private final DeviceCatalog catalog;

    public SharePageController(ShareBoardService boards, DeviceCatalog catalog) {
        this.boards = boards;
        this.catalog = catalog;
    }

    @GetMapping("/share/{token}")
    public ResponseEntity<?> page(@PathVariable String token) {
        if (boards.resolveByToken(token) == null) {
            return unavailablePage();
        }
        Resource resource = new ClassPathResource("static/share.html");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + "; charset=UTF-8")
                .body(resource);
    }

    @GetMapping(value = "/share/{token}/board", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> board(@PathVariable String token) {
        ShareBoard b = boards.resolveByToken(token);
        if (b == null) {
            return ResponseEntity.status(410).body(Map.of("error", "共享看板不可用、已过期或已停用"));
        }
        List<BoardSlot> defs = BoardLayouts.slots(b.layout);
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int i = 0; i < defs.size(); i++) {
            BoardSlot def = defs.get(i);
            String did = b.cameras.get(i);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slot", def.key());
            m.put("colStart", def.colStart());
            m.put("rowStart", def.rowStart());
            m.put("colSpan", def.colSpan());
            m.put("rowSpan", def.rowSpan());

            if (did == null) {
                // 空置槽位：不查设备、不下发取流路径
                m.put("did", null);
                m.put("cameraName", "空置");
                m.put("online", false);
                m.put("hlsUrl", null);
            } else {
                CameraDevice cam = catalog.find(did).orElse(null);
                m.put("did", did);
                m.put("cameraName", cam == null ? did : cam.name());
                m.put("online", cam != null && cam.online());
                // 相对站点根的路径，前端基于 document.baseURI 解析，兼容网关前缀
                m.put("hlsUrl", "share/" + token + "/hls/" + did + "/index.m3u8");
            }
            slots.add(m);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("name", b.name);
        resp.put("layout", b.layout);
        resp.put("slots", slots);
        resp.put("eventsUrl", "share/" + token + "/events");
        resp.put("serverTime", System.currentTimeMillis());
        resp.put("expireAt", b.expireAt);
        return ResponseEntity.ok(resp);
    }

    /** 失效时的极简提示页（不依赖任何外部资源）。 */
    private ResponseEntity<String> unavailablePage() {
        String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                + "<title>共享不可用</title></head>"
                + "<body style=\"margin:0;background:#0f172a;color:#e2e8f0;display:flex;"
                + "align-items:center;justify-content:center;height:100vh;font-family:sans-serif;\">"
                + "<div style=\"text-align:center\"><h2>共享看板不可用</h2>"
                + "<p style=\"color:#94a3b8\">链接可能已过期或已被停用，请联系管理员。</p></div></body></html>";
        return ResponseEntity.status(410)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + "; charset=UTF-8")
                .body(html);
    }
}
