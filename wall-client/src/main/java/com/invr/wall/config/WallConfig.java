package com.invr.wall.config;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 大屏客户端本地配置：从管理员分享的"共享链接"解析出服务端基址与 shareToken。
 *
 * <p>支持两种链接形态：</p>
 * <ul>
 *   <li>直连：{@code http://192.168.1.10:9080/share/<token>}</li>
 *   <li>fnOS 网关前缀：{@code http://host:port/app/<appname>/share/<token>}，
 *       路径中 {@code /share/} 之前的部分整体保留为 contextPath。</li>
 * </ul>
 *
 * <p>{@code rtspPortOverride} 为可选项：board 响应未携带 rtsp 字段（旧版服务端）时使用，
 * 正常情况下以服务端下发的实际 RTSP 监听端口为准。</p>
 */
public class WallConfig {

    /** 规范化后的服务端基址，无尾斜杠，如 http://192.168.1.10:9080 或 http://host/app/xxx */
    public String baseUrl;
    /** 共享令牌（URL 路径中的 256bit base64url 随机串）。 */
    public String token;
    /** RTSP 端口手动覆盖；null 表示用 board 下发端口，再回退默认 9554。 */
    public Integer rtspPortOverride;

    public String boardUrl() {
        return baseUrl + "/share/" + token + "/board";
    }

    public String eventsUrl() {
        return baseUrl + "/share/" + token + "/events";
    }

    /**
     * 解析用户粘贴的共享链接；同时允许只填服务端基址 + token 的高级表单组合（本方法只处理链接）。
     *
     * @throws IllegalArgumentException 链接格式不合法或路径中不含 /share/ 段
     */
    public static WallConfig parseShareLink(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("共享链接不能为空");
        }
        String raw = input.trim();
        final URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("链接格式无法解析：" + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("链接需以 http:// 或 https:// 开头");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("链接中缺少主机地址");
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        int marker = path.lastIndexOf("/share/");
        if (marker < 0) {
            throw new IllegalArgumentException("链接中未找到 /share/<令牌> 段，请复制完整共享链接");
        }
        String contextPath = path.substring(0, marker); // 网关前缀（直连时为空串）
        String tail = path.substring(marker + "/share/".length());
        int slash = tail.indexOf('/');
        String token = (slash >= 0 ? tail.substring(0, slash) : tail).trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException("共享链接缺少令牌段");
        }

        WallConfig c = new WallConfig();
        StringBuilder base = new StringBuilder();
        base.append(scheme.toLowerCase()).append("://").append(uri.getHost());
        int port = uri.getPort();
        if (port >= 0) {
            base.append(':').append(port);
        }
        base.append(contextPath);
        c.baseUrl = base.toString();
        c.token = token;
        return c;
    }
}
