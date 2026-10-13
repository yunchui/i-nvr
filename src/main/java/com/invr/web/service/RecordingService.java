package com.invr.web.service;

import com.invr.hub.StreamHub;
import com.invr.hub.CameraConnector;
import com.invr.model.CameraDevice;
import com.invr.record.RecordingSink;
import com.invr.store.RecordingStateStore;
import com.invr.web.api.ApiException;
import com.invr.web.config.NvrProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MP4 分段录像管理：按 DID 启停 {@link RecordingSink}（订阅全局 StreamHub，与预览/RTSP 共享单通道），
 * 枚举 / 下载 / 删除录像文件。
 */
@Service
public class RecordingService {

    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);
    /** rec-&lt;did&gt;-&lt;序号&gt;-&lt;时间&gt;.mp4 */
    private static final Pattern FILE_PATTERN =
            Pattern.compile("rec-(.+?)-\\d{3}-\\d{8}-\\d{6}\\.mp4");
    /** 时间轴用：捕获 did、序号、日期(yyyyMMdd)、时间(HHmmss)。 */
    private static final Pattern TIMELINE_PATTERN =
            Pattern.compile("rec-(.+?)-(\\d{3})-(\\d{8})-(\\d{6})\\.mp4");
    private static final DateTimeFormatter NAME_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("HHmmss");
    /** 相邻分段起点间隔不超过该值（毫秒）视为同一次连续录像，用于推断中间段时长。 */
    private static final long MERGE_GAP_MS = 300_000L;

    private final NvrProperties props;
    private final StreamHub hub;
    private final CameraConnector connector;
    private final RecordingStateStore stateStore;
    private final File rootDir;
    private final Map<String, RecordingSink> active = new ConcurrentHashMap<>();

    public RecordingService(NvrProperties props, StreamHub hub, CameraConnector connector,
                            RecordingStateStore stateStore) {
        this.props = props;
        this.hub = hub;
        this.connector = connector;
        this.stateStore = stateStore;
        this.rootDir = new File(props.getRecordingDir());
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            log.warn("录像目录创建失败：{}", rootDir.getAbsolutePath());
        }
    }

    public File rootDir() {
        return rootDir;
    }

    public boolean isRecording(String did) {
        return active.containsKey(did);
    }

    public Map<String, Object> start(String did, Long segmentSec) {
        if (active.containsKey(did)) {
            throw ApiException.conflict("该摄像头已在录像中");
        }
        CameraDevice device = connector.findDevice(did);
        if (device == null) {
            throw ApiException.notFound("设备列表中未找到 did=" + did);
        }
        long seg = segmentSec == null || segmentSec <= 0 ? props.getRecordSegmentSec() : segmentSec;
        RecordingSink sink = new RecordingSink(did, rootDir, seg);
        hub.subscribe(did, device.model(), connector.openerFor(device), sink);
        active.put(did, sink);
        // 记录“期望持续录像”：此后即便服务重启 / 崩溃，也会在启动后自动恢复（显式停止才移除）。
        stateStore.enable(did, seg);
        log.info("[{}] Web 触发录像开始（{}s/段）", did, seg);
        return Map.of("did", did, "recording", true, "segmentSec", seg);
    }

    public Map<String, Object> stop(String did) {
        RecordingSink sink = active.remove(did);
        if (sink == null) {
            throw ApiException.badRequest("该摄像头当前未在录像");
        }
        hub.unsubscribe(did, sink);
        sink.close();
        // 仅“显式停止”才取消持续录像标记；进程关闭 / 崩溃不走这里，重启后仍会恢复。
        stateStore.disable(did);
        log.info("[{}] Web 停止录像（视频包={} 分段={}）", did, sink.videoPackets(), sink.segmentCount());
        return Map.of("did", did, "recording", false,
                "videoPackets", sink.videoPackets(), "segments", sink.segmentCount());
    }

    /**
     * 对账：把持久化的“期望持续录像”状态收敛到实际订阅。
     *
     * <p>服务启动后、以及登录成功后由 {@code RecordingRecovery} 周期调用。对“标记需要录像、
     * 当前却没在录”的摄像头尝试恢复订阅；设备暂不可达 / 未登录等失败不抛错，留待下一轮重试
     * （StreamHub 本身也会对已建立的订阅自动重连）。已在录的跳过，保证幂等。</p>
     *
     * @return 本轮新恢复的摄像头数量
     */
    public int reconcile() {
        Map<String, Long> wanted;
        try {
            wanted = stateStore.cameras();
        } catch (Exception e) {
            log.warn("读取持续录像状态失败，跳过本轮对账：{}", e.getMessage());
            return 0;
        }
        int restored = 0;
        for (Map.Entry<String, Long> e : wanted.entrySet()) {
            String did = e.getKey();
            if (active.containsKey(did)) {
                continue;
            }
            try {
                start(did, e.getValue());
                restored++;
                log.info("[{}] 检测到持续录像标记，已自动恢复录像", did);
            } catch (Exception ex) {
                log.debug("[{}] 持续录像暂未恢复，下轮重试：{}", did, ex.toString());
            }
        }
        return restored;
    }

    @PreDestroy
    public void stopAll() {
        active.forEach((did, sink) -> {
            try {
                hub.unsubscribe(did, sink);
                sink.close();
            } catch (Exception e) {
                log.warn("[{}] 关闭录像失败：{}", did, e.toString());
            }
        });
        active.clear();
    }

    /** 枚举录像分段文件（仅根目录下的 mp4，HLS 缓存子目录不计）。 */
    public List<Map<String, Object>> list(String didFilter) throws IOException {
        File[] files = rootDir.listFiles((d, name) ->
                name.endsWith(".mp4") && FILE_PATTERN.matcher(name).matches());
        List<Map<String, Object>> out = new ArrayList<>();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            Matcher m = FILE_PATTERN.matcher(f.getName());
            if (!m.matches()) {
                continue;
            }
            String did = m.group(1);
            if (didFilter != null && !didFilter.isBlank() && !didFilter.equals(did)) {
                continue;
            }
            // 只有“当前正在写入、尚未封段”的那一个文件标记为录制中；
            // 已切完的历史分段即使该设备仍在录像，也不再显示录制中。
            RecordingSink sink = active.get(did);
            boolean recording = sink != null && f.getName().equals(sink.currentSegmentName());
            out.add(Map.of(
                    "did", did,
                    "name", f.getName(),
                    "size", f.length(),
                    "lastModified", f.lastModified(),
                    "recording", recording));
        }
        out.sort(Comparator.comparingLong((Map<String, Object> x) -> (long) x.get("lastModified")).reversed());
        return out;
    }

    /**
     * 时间轴数据：某摄像头在指定日期（本地时区，{@code yyyy-MM-dd}，为空取今天）的全部分段，
     * 按开始时间升序，每段含 {@code startMs/durMs/size/recording}。
     *
     * <p>段开始时间取自文件名中的墙钟时间；段时长估算规则：连续会话内（相邻段起点间隔
     * ≤{@link #MERGE_GAP_MS}）取与下一段起点的间隔，末段 / 孤立段取“文件最后修改时间−起点”，
     * 正在写入的段则随当前时间增长。时间轴用于导航，段内精确定位以播放器真实 metadata 为准。</p>
     */
    public Map<String, Object> timeline(String did, String date) throws IOException {
        LocalDate day;
        if (date == null || date.isBlank()) {
            day = LocalDate.now();
        } else {
            try {
                day = LocalDate.parse(date);
            } catch (RuntimeException e) {
                throw ApiException.badRequest("日期格式应为 yyyy-MM-dd：" + date);
            }
        }
        String dayPart = day.format(NAME_DATE);
        long nominalMs = props.getRecordSegmentSec() * 1000L;

        File[] files = rootDir.listFiles((d, name) ->
                name.endsWith(".mp4") && TIMELINE_PATTERN.matcher(name).matches());
        List<Map<String, Object>> segs = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                Matcher m = TIMELINE_PATTERN.matcher(f.getName());
                if (!m.matches() || !did.equals(m.group(1)) || !dayPart.equals(m.group(3))) {
                    continue;
                }
                LocalDateTime ldt = LocalDateTime.of(
                        LocalDate.parse(m.group(3), NAME_DATE),
                        LocalTime.parse(m.group(4), NAME_TIME));
                long startMs = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                RecordingSink sink = active.get(did);
                boolean recording = sink != null && f.getName().equals(sink.currentSegmentName());
                Map<String, Object> seg = new HashMap<>();
                seg.put("name", f.getName());
                seg.put("idx", Integer.parseInt(m.group(2)));
                seg.put("startMs", startMs);
                seg.put("size", f.length());
                seg.put("lastModified", f.lastModified());
                seg.put("recording", recording);
                segs.add(seg);
            }
        }
        segs.sort(Comparator.comparingLong(s -> (long) s.get("startMs")));

        long nowMs = System.currentTimeMillis();
        for (int i = 0; i < segs.size(); i++) {
            Map<String, Object> s = segs.get(i);
            long start = (long) s.get("startMs");
            long dur;
            if (i + 1 < segs.size()) {
                long gap = (long) segs.get(i + 1).get("startMs") - start;
                dur = (gap > 0 && gap <= MERGE_GAP_MS) ? gap : tailDuration(s, start, nominalMs, nowMs);
            } else {
                dur = tailDuration(s, start, nominalMs, nowMs);
            }
            s.put("durMs", dur);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("did", did);
        out.put("date", day.toString());
        out.put("segments", segs);
        return out;
    }

    /** 末段 / 孤立段（含正在写入段）的时长估算。 */
    private long tailDuration(Map<String, Object> seg, long startMs, long nominalMs, long nowMs) {
        boolean recording = (boolean) seg.get("recording");
        long ref = recording ? nowMs : (long) seg.get("lastModified");
        long dur = Math.max(1000L, ref - startMs);
        return Math.min(dur, nominalMs + 60_000L);
    }

    /** 安全解析录像文件（禁止路径穿越）。 */
    public File resolveFile(String name) {
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")
                || !name.endsWith(".mp4")) {
            throw ApiException.badRequest("非法文件名");
        }
        File f = new File(rootDir, name);
        if (!f.isFile()) {
            throw ApiException.notFound("录像文件不存在：" + name);
        }
        return f;
    }

    public void delete(String name) {
        File f = resolveFile(name);
        try {
            Files.deleteIfExists(f.toPath());
        } catch (IOException e) {
            throw ApiException.badRequest("删除失败：" + e.getMessage());
        }
    }
}
