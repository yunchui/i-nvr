package com.invr.web.service;

import com.invr.hls.HlsSink;
import com.invr.hub.CameraConnector;
import com.invr.hub.StreamHub;
import com.invr.model.CameraDevice;
import com.invr.store.AppSettingsStore;
import com.invr.web.api.ApiException;
import com.invr.web.config.NvrProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网页 HLS 预览：每个摄像头维护一个 {@link HlsSink}（fMP4/CMAF），作为独立消费者订阅
 * {@link StreamHub}。
 *
 * <p>所有参数支持在“设置”页运行时修改并持久化（settings.json，优先级高于 application.yml）：</p>
 * <ul>
 *   <li>{@code prewarm=true}：后台对在线摄像头提前建连并保持常驻，点击即播、首帧快；设备离线
 *       自动回收，重新上线自动预热；</li>
 *   <li>{@code prewarm=false}：按需订阅，空闲（无 HTTP 请求）超过阈值自动退订清理；</li>
 *   <li>分片时长/窗口/音频开关变更后，重建现有 sink 立即生效（预览会短暂中断）。</li>
 * </ul>
 * 同一摄像头多标签预览仍只占一条上游。
 */
@Service
public class HlsService {

    private static final Logger log = LoggerFactory.getLogger(HlsService.class);

    /** 首片就绪等待上限：覆盖冷启动（建连+等 IDR+攒片），超时给 503 让播放器重试，绝不返回空 m3u8。 */
    private static final long FIRST_SEGMENT_TIMEOUT_MS = 20_000;

    private final StreamHub hub;
    private final CameraConnector connector;
    private final DeviceCatalog catalog;
    private final NvrProperties props;
    private final AppSettingsStore settingsStore;
    private final Path hlsRoot;

    private final ConcurrentHashMap<String, HlsSink> sinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastAccess = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> janitorSeen = new ConcurrentHashMap<>();
    private Thread janitor;
    private volatile boolean running;

    public HlsService(StreamHub hub, CameraConnector connector, DeviceCatalog catalog,
                      NvrProperties props, AppSettingsStore settingsStore) {
        this.hub = hub;
        this.connector = connector;
        this.catalog = catalog;
        this.props = props;
        this.settingsStore = settingsStore;
        this.hlsRoot = new File(props.getRecordingDir()).toPath().resolve("_hls");
        this.hlsRoot.toFile().mkdirs();
        startJanitor();
        log.info("HLS(fMP4) 预览服务就绪（预热常驻={}，空闲 {}s 停止预览上游）",
                effectivePrewarm(), effectiveIdleSec());
    }

    // ===================== 生效参数（Web 设置优先，否则配置默认） =====================

    private int effectiveTargetSec() {
        Integer c = settingsStore.hlsTargetSec();
        return c != null ? c : props.getHls().getTargetSec();
    }

    private int effectiveWindow() {
        Integer c = settingsStore.hlsWindow();
        return c != null ? c : props.getHls().getWindow();
    }

    private int effectiveIdleSec() {
        Integer c = settingsStore.hlsIdleSec();
        return c != null ? c : props.getHls().getIdleSec();
    }

    private boolean effectiveAudio() {
        Boolean c = settingsStore.hlsAudio();
        return c != null ? c : props.getHls().isAudio();
    }

    private boolean effectivePrewarm() {
        Boolean c = settingsStore.hlsPrewarm();
        return c != null ? c : props.getHls().isPrewarm();
    }

    private void startJanitor() {
        running = true;
        janitor = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(3000);
                    long idleMs = effectiveIdleSec() * 1000L;
                    long now = System.currentTimeMillis();
                    boolean prewarm = effectivePrewarm();

                    // 预热：对在线且尚未订阅的设备提前建连（ensure 仅触发异步订阅，不阻塞）
                    if (prewarm) {
                        for (CameraDevice device : catalog.all()) {
                            if (device.online() && !sinks.containsKey(device.did())) {
                                try {
                                    ensure(device.did());
                                } catch (Throwable t) {
                                    log.debug("摄像头 {} 预热失败，下轮重试：{}", device.did(), t.toString());
                                }
                            }
                        }
                    }

                    // 回收：预热模式下仅回收离线设备（在线常驻）；非预热模式按空闲超时回收
                    for (String did : sinks.keySet()) {
                        boolean shouldRemove;
                        if (prewarm) {
                            shouldRemove = !isOnline(did);
                        } else {
                            long last = lastAccess.getOrDefault(did, now);
                            long seen = janitorSeen.getOrDefault(did, now);
                            if (now - last < idleMs) {
                                janitorSeen.put(did, now);
                                shouldRemove = false;
                            } else if (now - seen < idleMs) {
                                shouldRemove = false;
                            } else {
                                shouldRemove = true;
                            }
                        }
                        if (!shouldRemove) {
                            continue;
                        }
                        HlsSink sink = sinks.remove(did);
                        lastAccess.remove(did);
                        janitorSeen.remove(did);
                        if (sink != null) {
                            hub.unsubscribe(did, sink);
                            sink.purge();
                            log.info("摄像头 {} 已停止 HLS 预览（{}），清理 fMP4 分片",
                                    did, prewarm ? "设备离线" : "空闲超时");
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

    /** 设备在当前目录中且上报在线。 */
    private boolean isOnline(String did) {
        for (CameraDevice d : catalog.all()) {
            if (d.did().equals(did) && d.online()) {
                return true;
            }
        }
        return false;
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
            HlsSink sink = new HlsSink(d, dir, effectiveWindow(), effectiveTargetSec(), effectiveAudio());
            hub.subscribe(d, device.model(), connector.openerFor(device), sink);
            log.info("摄像头 {} 开始 HLS(fMP4) 预览订阅", d);
            return sink;
        });
    }

    /**
     * 取播放列表（首次调用触发订阅）。等待首片就绪：冷启动最长等 {@value #FIRST_SEGMENT_TIMEOUT_MS}ms；
     * 超时仍未就绪则抛 503 让播放器重试，避免把无分片的空 m3u8 交给原生播放器导致 error4。
     */
    public byte[] playlist(String did) throws InterruptedException {
        HlsSink sink = ensure(did);
        if (!sink.awaitFirstSegment(FIRST_SEGMENT_TIMEOUT_MS)) {
            throw new ApiException(503, "首片尚未就绪，请稍后重试");
        }
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

    // ===================== 设置页：读取 / 更新 =====================

    /** 当前 HLS 生效参数与配置默认值（供设置页展示）。 */
    public Map<String, Object> hlsSettingsInfo() {
        NvrProperties.Hls d = props.getHls();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("targetSec", effectiveTargetSec());
        m.put("defaultTargetSec", d.getTargetSec());
        m.put("window", effectiveWindow());
        m.put("defaultWindow", d.getWindow());
        m.put("idleSec", effectiveIdleSec());
        m.put("defaultIdleSec", d.getIdleSec());
        m.put("audio", effectiveAudio());
        m.put("defaultAudio", d.isAudio());
        m.put("prewarm", effectivePrewarm());
        m.put("defaultPrewarm", d.isPrewarm());
        return m;
    }

    /**
     * 运行时更新 HLS 参数并持久化。校验后落盘，再重建所有现有 sink，使分片时长/窗口/音频立即生效
     * （正在进行的预览会短暂中断后自动恢复）；空闲/预热开关下一轮 janitor 即生效。
     */
    public synchronized Map<String, Object> updateHls(int targetSec, int window, int idleSec,
                                                      boolean audio, boolean prewarm) {
        if (targetSec < 1 || targetSec > 10) {
            throw ApiException.badRequest("HLS 分片时长需在 1～10 秒之间");
        }
        if (window < 3 || window > 20) {
            throw ApiException.badRequest("HLS 窗口分片数需在 3～20 之间");
        }
        if (idleSec < 10 || idleSec > 3600) {
            throw ApiException.badRequest("HLS 空闲回收秒数需在 10～3600 之间");
        }
        NvrProperties.Hls d = props.getHls();
        settingsStore.saveHls(
                targetSec == d.getTargetSec() ? null : targetSec,
                window == d.getWindow() ? null : window,
                idleSec == d.getIdleSec() ? null : idleSec,
                audio == d.isAudio() ? null : audio,
                prewarm == d.isPrewarm() ? null : prewarm);
        rebuildAllSinks();
        log.info("HLS 预览参数已更新：分片 {}s、窗口 {}、空闲 {}s、音频 {}、预热 {}",
                targetSec, window, idleSec, audio, prewarm);
        return hlsSettingsInfo();
    }

    /** 用当前生效参数重建所有 sink（先退订清理旧 sink，再 ensure 新建）。 */
    private void rebuildAllSinks() {
        List<String> dids = new ArrayList<>(sinks.keySet());
        for (String did : dids) {
            HlsSink old = sinks.remove(did);
            lastAccess.remove(did);
            janitorSeen.remove(did);
            if (old != null) {
                hub.unsubscribe(did, old);
                old.purge();
            }
            try {
                ensure(did);
            } catch (Throwable t) {
                log.debug("摄像头 {} 重建 HLS sink 失败，下轮预热重试：{}", did, t.toString());
            }
        }
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
