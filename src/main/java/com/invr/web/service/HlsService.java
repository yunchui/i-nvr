package com.invr.web.service;

import com.invr.hls.HlsSink;
import com.invr.hub.CameraConnector;
import com.invr.hub.StreamHub;
import com.invr.model.CameraDevice;
import com.invr.web.api.ApiException;
import com.invr.web.config.NvrProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网页 HLS 预览：每个摄像头维护一个 {@link HlsSink}（fMP4/CMAF），作为独立消费者订阅
 * {@link StreamHub}。空闲（无 HTTP 请求）超过阈值自动退订并清理分片；同一摄像头多标签预览
 * 仍只占一条上游。
 */
@Service
public class HlsService {

    private static final Logger log = LoggerFactory.getLogger(HlsService.class);

    private final StreamHub hub;
    private final CameraConnector connector;
    private final NvrProperties props;
    private final Path hlsRoot;

    private final ConcurrentHashMap<String, HlsSink> sinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastAccess = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> janitorSeen = new ConcurrentHashMap<>();
    private Thread janitor;
    private volatile boolean running;

    public HlsService(StreamHub hub, CameraConnector connector, NvrProperties props) {
        this.hub = hub;
        this.connector = connector;
        this.props = props;
        this.hlsRoot = new File(props.getRecordingDir()).toPath().resolve("_hls");
        this.hlsRoot.toFile().mkdirs();
        startJanitor();
        log.info("HLS(fMP4) 预览服务就绪（空闲 {}s 停止预览上游）", props.getHls().getIdleSec());
    }

    private void startJanitor() {
        running = true;
        janitor = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(3000);
                    long idleMs = props.getHls().getIdleSec() * 1000L;
                    long now = System.currentTimeMillis();
                    for (String did : sinks.keySet()) {
                        long last = lastAccess.getOrDefault(did, now);
                        long seen = janitorSeen.getOrDefault(did, now);
                        if (now - last < idleMs) {
                            janitorSeen.put(did, now);
                            continue;
                        }
                        if (now - seen < idleMs) {
                            continue;
                        }
                        HlsSink sink = sinks.remove(did);
                        lastAccess.remove(did);
                        janitorSeen.remove(did);
                        if (sink != null) {
                            hub.unsubscribe(did, sink);
                            sink.purge();
                            log.info("摄像头 {} 预览空闲超时，已停止上游并清理 fMP4 分片", did);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    log.debug("HLS janitor 异常：{}", t.toString());
                }
            }
        }, "hls-janitor");
        janitor.setDaemon(true);
        janitor.start();
    }

    private HlsSink ensure(String did) {
        HlsSink existing = sinks.get(did);
        if (existing != null) {
            return existing;
        }
        CameraDevice device = connector.findDevice(did);
        if (device == null) {
            throw ApiException.notFound("设备列表中未找到 did=" + did);
        }
        return sinks.computeIfAbsent(did, d -> {
            File dir = hlsRoot.resolve(d).toFile();
            dir.mkdirs();
            NvrProperties.Hls h = props.getHls();
            HlsSink sink = new HlsSink(d, dir, h.getWindow(), h.getTargetSec(), h.isAudio());
            hub.subscribe(d, device.model(), connector.openerFor(device), sink);
            log.info("摄像头 {} 开始 HLS(fMP4) 预览订阅", d);
            return sink;
        });
    }

    /** 取播放列表（首次调用触发订阅；等待 init.mp4 与首个分片落盘）。 */
    public byte[] playlist(String did) throws InterruptedException {
        HlsSink sink = ensure(did);
        sink.awaitFirstSegment(10_000);
        touch(did);
        return sink.playlistBytes();
    }

    /**
     * 取 init.mp4 / seg-N.m4s 文件；仅在预览进行中可用，否则返回 null（调用方给 404，
     * 播放器随后重新拉 m3u8 会重新触发订阅）。
     */
    public File mediaFile(String did, String name) {
        HlsSink sink = sinks.get(did);
        if (sink == null) {
            return null;
        }
        touch(did);
        return sink.mediaFile(name);
    }

    private void touch(String did) {
        long now = System.currentTimeMillis();
        lastAccess.put(did, now);
        janitorSeen.put(did, now);
    }

    public boolean isPreviewing(String did) {
        return sinks.containsKey(did);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (janitor != null) {
            janitor.interrupt();
        }
        for (var e : sinks.entrySet()) {
            hub.unsubscribe(e.getKey(), e.getValue());
            e.getValue().purge();
        }
        sinks.clear();
    }
}
