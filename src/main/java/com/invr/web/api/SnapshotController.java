package com.invr.web.api;

import com.invr.web.service.SessionHolder;
import com.invr.web.service.SnapshotService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放接口：按摄像头 DID 实时截取一帧画面并以 JPEG 返回。
 *
 * <p>{@code GET /api/snapshot/{did}} → {@code image/jpeg} 二进制。该接口除网页门禁令牌外，
 * 还允许凭“设置页”签发的 API 密钥访问（请求头 {@code X-API-Key: <密钥>}），供外部系统/脚本
 * 集成；可访问范围在 {@link com.invr.web.config.AccessInterceptor} 中以白名单显式限定，
 * API 密钥不能用于任何其他管理接口。</p>
 *
 * <p>与 {@link HlsController} 同理，直接用 {@link HttpServletResponse} 写纯二进制 MIME，
 * 避免内容协商给 {@code image/jpeg} 追加字符集。</p>
 */
@RestController
@RequestMapping("/api")
public class SnapshotController {

    private final SnapshotService snapshotService;
    private final SessionHolder session;

    public SnapshotController(SnapshotService snapshotService, SessionHolder session) {
        this.snapshotService = snapshotService;
        this.session = session;
    }

    @GetMapping("/snapshot/{did}")
    public void snapshot(@PathVariable String did, HttpServletResponse resp) throws Exception {
        if (!session.isLoggedIn()) {
            resp.sendError(HttpServletResponse.SC_GONE, "米家账号未登录");
            return;
        }
        byte[] jpeg = snapshotService.capture(did);
        resp.setContentType("image/jpeg");
        resp.setHeader("Cache-Control", "no-store");
        resp.setContentLength(jpeg.length);
        resp.getOutputStream().write(jpeg);
    }
}
