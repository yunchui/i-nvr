package com.invr.wall.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.invr.wall.support.Json;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 共享看板 SSE 客户端（{@code GET /share/{token}/events}）。
 *
 * <p>不依赖 okhttp-sse 插件，自行按 SSE 帧协议切分（空行分隔事件、{@code data:} 负载、
 * {@code :} 注释心跳），便于精确实现：建连 410 永久失效、断线指数退避（封顶 30s）、
 * 重连后由服务端 snapshot 自动校准事件态。回调均在内部线程触发，UI 层自行切线程。</p>
 */
public final class SseClient {

    private static final Logger log = LoggerFactory.getLogger(SseClient.class);
    private static final long MAX_BACKOFF_SEC = 30L;

    /** 事件回调；did/expireAt 仅 snapshot(active=true) 与 switch 携带。 */
    public interface Listener {
        default void onConnected() {
        }

        default void onDisconnected() {
        }

        /** 建连或重连收到快照。 */
        void onSnapshot(boolean active, String did, Long expireAt);

        void onSwitch(String did, long expireAt);

        void onEnd();

        /** 看板 410，不可恢复。 */
        void onGone();
    }

    private final OkHttpClient http;
    private final String url;
    private final Listener listener;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile Thread worker;

    public SseClient(OkHttpClient http, String url, Listener listener) {
        this.http = http;
        this.url = url;
        this.listener = listener;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wall-sse");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        executor.submit(this::loop);
    }

    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            executor.shutdownNow();
            Thread w = worker;
            if (w != null) {
                w.interrupt();
            }
        }
    }

    private void loop() {
        worker = Thread.currentThread();
        long backoffSec = 1L;
        boolean everConnected = false;
        while (!stopped.get()) {
            boolean connectedThisRound = false;
            try {
                connectedThisRound = connectAndRead();
            } catch (GoneSignal g) {
                log.warn("SSE 建连返回 410，看板已失效");
                safeOnGone();
                stop();
                return;
            } catch (Exception e) {
                if (stopped.get()) {
                    return;
                }
                log.warn("SSE 连接异常：{}", e.toString());
            }
            if (stopped.get()) {
                return;
            }
            if (connectedThisRound) {
                backoffSec = 1L;
                everConnected = true;
            }
            try {
                listener.onDisconnected();
            } catch (Exception ignore) {
                // 回调异常不杀重连循环
            }
            long wait = Math.min(backoffSec, MAX_BACKOFF_SEC);
            log.info("SSE {}秒后重连", wait);
            try {
                TimeUnit.SECONDS.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
            backoffSec = Math.min(backoffSec * 2L, MAX_BACKOFF_SEC);
            if (!everConnected) {
                // 首次建连前的退避保留渐进，连接成功过则固定快速重试序列
                backoffSec = Math.max(backoffSec, 2L);
            }
        }
    }

    /**
     * 阻塞读取一个 SSE 连接直到结束。
     *
     * @return true 表示曾成功建连并正常读到流结束；410 抛 GoneSignal
     */
    private boolean connectAndRead() throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .cacheControl(new okhttp3.CacheControl.Builder().noStore().build())
                .build();
        Response resp = http.newCall(req).execute();
        if (resp.code() == 410) {
            resp.close();
            throw new GoneSignal();
        }
        ResponseBody body = resp.body();
        if (!resp.isSuccessful() || body == null) {
            resp.close();
            throw new IOException("SSE 返回 HTTP " + resp.code());
        }
        listener.onConnected();
        try (ResponseBody ignored = body) {
            BufferedSource source = body.source();
            StringBuilder dataBuf = new StringBuilder();
            while (!stopped.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break; // 服务端关闭连接
                }
                if (line.isEmpty()) {
                    dispatch(dataBuf);
                    dataBuf.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue; // SSE 注释 / 心跳
                }
                if (line.startsWith("data:")) {
                    if (dataBuf.length() > 0) {
                        dataBuf.append('\n');
                    }
                    String payload = line.substring(5);
                    if (payload.startsWith(" ")) {
                        payload = payload.substring(1);
                    }
                    dataBuf.append(payload);
                }
            }
        } finally {
            resp.close();
        }
        return true;
    }

    private void dispatch(StringBuilder dataBuf) {
        if (dataBuf.length() == 0) {
            return;
        }
        try {
            JsonNode node = Json.MAPPER.readTree(dataBuf.toString());
            String type = node.path("type").asText("");
            switch (type) {
                case "snapshot" -> {
                    boolean active = node.path("active").asBoolean(false);
                    listener.onSnapshot(active,
                            node.path("did").asText(null),
                            node.has("expireAt") && !node.path("expireAt").isNull()
                                    ? node.path("expireAt").asLong() : null);
                }
                case "switch" -> listener.onSwitch(node.path("did").asText(null),
                        node.path("expireAt").asLong(0L));
                case "end" -> listener.onEnd();
                default -> log.debug("忽略未知 SSE 消息类型：{}", type);
            }
        } catch (Exception e) {
            log.warn("SSE 消息解析失败，已丢弃：{}", e.toString());
        }
    }

    private void safeOnGone() {
        try {
            listener.onGone();
        } catch (Exception e) {
            log.error("onGone 回调异常：{}", e.toString());
        }
    }

    private static final class GoneSignal extends Exception {
    }
}
