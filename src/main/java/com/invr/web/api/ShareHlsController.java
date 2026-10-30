package com.invr.web.api;

import com.invr.model.ShareBoard;
import com.invr.web.service.HlsService;
import com.invr.web.service.ShareBoardService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * 免鉴权共享 HLS 取流：{@code /share/{token}/hls/{did}/...}。
 *
 * <p>两道授权：令牌有效（启用且未过期）且 did 属于该看板摄像头清单；通过后<strong>直接复用
 * {@link HlsService}</strong>——同一 did 与管理员预览、其他电视共享同一个 {@code HlsSink}，
 * 上游仍只占一条 CS2。m3u8 原样返回（子资源为同目录相对路径，无需注入会话令牌）。</p>
 *
 * <p>Content-Type 沿用纯 MIME、不追加字符集，理由同 {@link HlsController}。</p>
 */
@RestController
public class ShareHlsController {

    private final ShareBoardService boards;
    private final HlsService hls;

    public ShareHlsController(ShareBoardService boards, HlsService hls) {
        this.boards = boards;
        this.hls = hls;
    }

    @GetMapping("/share/{token}/hls/{did}/index.m3u8")
    public void playlist(@PathVariable String token, @PathVariable String did,
                         HttpServletResponse resp) throws Exception {
        if (!authorized(token, did)) {
            resp.sendError(HttpServletResponse.SC_GONE);
            return;
        }
        byte[] body = hls.playlist(did);
        resp.setContentType("application/vnd.apple.mpegurl");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setContentLength(body.length);
        resp.getOutputStream().write(body);
    }

    @GetMapping("/share/{token}/hls/{did}/init.mp4")
    public void init(@PathVariable String token, @PathVariable String did,
                     HttpServletResponse resp) throws Exception {
        writeMedia(token, did, "init.mp4", "video/mp4", resp);
    }

    @GetMapping("/share/{token}/hls/{did}/{filename:seg-\\d+\\.m4s}")
    public void segment(@PathVariable String token, @PathVariable String did,
                        @PathVariable String filename, HttpServletResponse resp) throws Exception {
        writeMedia(token, did, filename, "video/iso.segment", resp);
    }

    /** 授权：看板可用且 did 在其摄像头列表内。 */
    private boolean authorized(String token, String did) {
        ShareBoard b = boards.resolveByToken(token);
        return b != null && b.cameras.contains(did);
    }

    private void writeMedia(String token, String did, String name, String mime,
                            HttpServletResponse resp) throws Exception {
        if (!authorized(token, did)) {
            resp.sendError(HttpServletResponse.SC_GONE);
            return;
        }
        File f = hls.mediaFile(did, name);
        if (f == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        resp.setContentType(mime);
        resp.setHeader("Cache-Control", "no-cache");
        resp.setContentLengthLong(f.length());
        try (OutputStream out = resp.getOutputStream()) {
            Files.copy(f.toPath(), out);
        }
    }
}
