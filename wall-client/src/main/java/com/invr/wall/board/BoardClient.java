package com.invr.wall.board;

import com.invr.wall.config.WallConfig;
import com.invr.wall.support.Json;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;

/**
 * 共享看板只读 HTTP 客户端：拉取 board 配置、拼接每路 RTSP 地址。
 *
 * <p>RTSP 主机/端口解析优先级：</p>
 * <ol>
 *   <li>本地手动覆盖端口（rtspPortOverride）+ board 下发主机；</li>
 *   <li>board 响应的 rtsp.host/rtsp.port（新版服务端，端口反映运行时 rebind）；</li>
 *   <li>回退：共享链接主机 + 默认 9554（旧版服务端）。</li>
 * </ol>
 */
public class BoardClient {

    /** board 未下发 RTSP 端口时的历史默认值。 */
    public static final int FALLBACK_RTSP_PORT = 9554;

    private static final Logger log = LoggerFactory.getLogger(BoardClient.class);

    private final OkHttpClient http;
    private final WallConfig config;

    public BoardClient(OkHttpClient http, WallConfig config) {
        this.http = http;
        this.config = config;
    }

    /** 拉取看板配置；410 抛 {@link BoardGoneException}，其他非 2xx/网络错误抛 IOException。 */
    public BoardDto fetchBoard() throws BoardGoneException, IOException {
        Request req = new Request.Builder()
                .url(config.boardUrl())
                .header("Accept", "application/json")
                .cacheControl(new okhttp3.CacheControl.Builder().noStore().build())
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 410) {
                throw new BoardGoneException("共享看板不可用、已过期或已停用");
            }
            ResponseBody body = resp.body();
            if (!resp.isSuccessful() || body == null) {
                throw new IOException("看板接口返回 HTTP " + resp.code());
            }
            BoardDto dto = Json.MAPPER.readValue(body.byteStream(), BoardDto.class);
            if (dto == null || dto.layout == null || dto.slots == null) {
                throw new IOException("看板响应缺少 layout/slots 字段");
            }
            return dto;
        }
    }

    /** 根据 board 坐标与 did 拼 RTSP 拉流地址。 */
    public String rtspUrlFor(BoardDto board, String did) {
        String host = config.baseUrl;
        try {
            host = URI.create(config.baseUrl).getHost();
        } catch (Exception ignore) {
            // 保留 baseUrl 兜底，下面 IPv6/括号处理仍会执行
        }
        if (board.rtsp != null && board.rtsp.host != null && !board.rtsp.host.isBlank()) {
            host = board.rtsp.host;
        }
        int port;
        if (config.rtspPortOverride != null && config.rtspPortOverride > 0) {
            port = config.rtspPortOverride;
        } else if (board.rtsp != null && board.rtsp.port > 0) {
            port = board.rtsp.port;
        } else {
            port = FALLBACK_RTSP_PORT;
        }
        // IPv6 字面量在 URL 中需加方括号
        if (host != null && host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        String url = "rtsp://" + host + ":" + port + "/" + did;
        log.debug("RTSP 地址 did={} -> {}", did, url);
        return url;
    }
}
