package com.invr.web.api;

import com.invr.web.service.HlsService;
import com.invr.web.service.SessionHolder;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * HLS（fMP4/CMAF）直播端点：播放列表 + 初始化段 + 媒体分片。
 *
 * <ul>
 *   <li>{@code GET /hls/{did}/index.m3u8}：播放列表（含 #EXT-X-MAP:init.mp4）；</li>
 *   <li>{@code GET /hls/{did}/init.mp4}：fMP4 初始化段（hvcC/avcC + AAC esds）；</li>
 *   <li>{@code GET /hls/{did}/seg-N.m4s}：CMAF 媒体分片。</li>
 * </ul>
 *
 * <p><b>直接用 {@link HttpServletResponse} 写字节与 Content-Type</b>：经 ResponseEntity +
 * Spring 内容协商时，会给二进制 MIME 错误追加 {@code ;charset=UTF-8}（Tomcat 还会对未知扩展名
 * .m4s 补 {@code Content-Disposition: inline;filename=f.txt}），Edge 原生 HLS 据此无法识别
 * fMP4 初始化段/分片，直接 MEDIA_ERR_SRC_NOT_SUPPORTED（黑屏 error 4）。{@code setContentType}
 * 原样输出纯 MIME，不追加字符集。</p>
 */
@RestController
@RequestMapping("/hls")
public class HlsController {

    private final HlsService hls;
    private final SessionHolder session;

    public HlsController(HlsService hls, SessionHolder session) {
        this.hls = hls;
        this.session = session;
    }

    @GetMapping("/{did}/index.m3u8")
    public void playlist(@PathVariable String did, HttpServletResponse resp) throws Exception {
        if (!session.isLoggedIn()) {
            resp.sendError(HttpServletResponse.SC_GONE);
            return;
        }
        byte[] body = hls.playlist(did);
        resp.setContentType("application/vnd.apple.mpegurl");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setContentLength(body.length);
        resp.getOutputStream().write(body);
    }

    @GetMapping("/{did}/init.mp4")
    public void initSegment(@PathVariable String did, HttpServletResponse resp) throws Exception {
        writeMedia(did, "init.mp4", "video/mp4", resp);
    }

    @GetMapping("/{did}/{filename:seg-\\d+\\.m4s}")
    public void mediaSegment(@PathVariable String did,
                             @PathVariable String filename,
                             HttpServletResponse resp) throws Exception {
        writeMedia(did, filename, "video/iso.segment", resp);
    }

    private void writeMedia(String did, String name, String mime, HttpServletResponse resp) throws Exception {
        if (!session.isLoggedIn()) {
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
