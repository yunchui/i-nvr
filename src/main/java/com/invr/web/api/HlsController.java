package com.invr.web.api;

import com.invr.web.config.TokenSessionFilter;
import com.invr.web.service.HlsService;
import com.invr.web.service.SessionHolder;
import com.invr.web.service.TokenSessionManager.TokenSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
 *
 * <p><b>令牌注入</b>：Edge/Safari 原生 HLS 由播放器自行请求 m3u8 中的 init.mp4 / seg-N.m4s，
 * 无法附加 Authorization 头；播放列表返回前把当前令牌以 {@code ?__invr_t=} 注入每个子资源
 * URI，保证原生播放器的分片请求也能通过门禁。hls.js 则额外通过 xhrSetup 带头。</p>
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
    public void playlist(@PathVariable String did, HttpServletRequest request,
                         HttpServletResponse resp) throws Exception {
        if (!session.isLoggedIn()) {
            resp.sendError(HttpServletResponse.SC_GONE);
            return;
        }
        byte[] body = hls.playlist(did);
        TokenSession tokenSession = TokenSessionFilter.currentSession(request);
        if (tokenSession != null) {
            body = appendTokenToPlaylist(body, tokenSession.getToken());
        }
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

    /**
     * 把令牌查询参数追加到 m3u8 内每一个子资源 URI：
     * 标签行里的 {@code URI="init.mp4"}（#EXT-X-MAP）与独占一行的 {@code seg-N.m4s} 分片。
     * 这样原生 HLS 播放器（无法加 Authorization 头）请求分片时也会携带门禁令牌。
     */
    static byte[] appendTokenToPlaylist(byte[] playlistBytes, String token) {
        String text = new String(playlistBytes, StandardCharsets.UTF_8);
        String query = TokenSessionFilter.QUERY_PARAM + "=" + token;
        StringBuilder out = new StringBuilder(text.length() + 256);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int uriIdx = line.indexOf("URI=\"");
            if (uriIdx >= 0) {
                int valueStart = uriIdx + 5;
                int valueEnd = line.indexOf('"', valueStart);
                if (valueEnd > valueStart) {
                    String uri = line.substring(valueStart, valueEnd);
                    String sep = uri.contains("?") ? "&" : "?";
                    line = line.substring(0, valueStart) + uri + sep + query + line.substring(valueEnd);
                }
            } else if (!line.startsWith("#") && !line.isBlank()) {
                String sep = line.contains("?") ? "&" : "?";
                line = line + sep + query;
            }
            out.append(line);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
