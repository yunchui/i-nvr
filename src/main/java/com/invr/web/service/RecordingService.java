package com.invr.web.service;

import com.invr.hub.StreamHub;
import com.invr.hub.CameraConnector;
import com.invr.model.CameraDevice;
import com.invr.record.RecordingSink;
import com.invr.store.AppSettingsStore;
import com.invr.store.DataDir;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /**
     * rec-&lt;did&gt;-&lt;序号&gt;-&lt;时间&gt;.mp4。
     * 序号用 {@code %03d} 生成（至少 3 位、不足补零），超过 999 后自然变 4 位（1000、1001…），
     * 故此处按“3 位及以上”匹配，避免长期录像序号超过 999 后无法识别。
     */
    private static final Pattern FILE_PATTERN =
            Pattern.compile("rec-(.+?)-\\d{3,}-\\d{8}-\\d{6}\\.mp4");
    /** 时间轴用：捕获 did、序号、日期(yyyyMMdd)、时间(HHmmss)。 */
    private static final Pattern TIMELINE_PATTERN =
            Pattern.compile("rec-(.+?)-(\\d{3,})-(\\d{8})-(\\d{6})\\.mp4");
    private static final DateTimeFormatter NAME_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("HHmmss");
    /** 相邻分段起点间隔不超过该值（毫秒）视为同一次连续录像，用于推断中间段时长。 */
    private static final long MERGE_GAP_MS = 300_000L;

    private final NvrProperties props;
    private final StreamHub hub;
    private final CameraConnector connector;
    private final RecordingStateStore stateStore;
    private final AppSettingsStore settingsStore;
    /**
     * 录像根目录，运行时可在“设置”页切换（见 {@link #updateRecordingDir}），故用 volatile 而非 final；
     * 初始值优先取 Web 设置（settings.json），缺省回落到配置文件 {@code invr.recording-dir}。
     */
    private volatile File rootDir;
    /**
     * 生效的录像分段秒数，运行时可在“设置”页修改（见 {@link #updateSegmentSec}），volatile；
     * 初始值优先取 Web 设置（settings.json），缺省回落到配置文件 {@code invr.record-segment-sec}。
     */
    private volatile long segmentSec;
    /**
     * 生效的录像保留天数：0 表示永久保留（不自动清理），>0 时超出该天数的分段自动删除；
     * 运行时可在“设置”页修改，初始值优先取 Web 设置。
     */
    private volatile int retentionDays;
    private final Map<String, RecordingSink> active = new ConcurrentHashMap<>();

    public RecordingService(NvrProperties props, StreamHub hub, CameraConnector connector,
                            RecordingStateStore stateStore, AppSettingsStore settingsStore) {
        this.props = props;
        this.hub = hub;
        this.connector = connector;
        this.stateStore = stateStore;
        this.settingsStore = settingsStore;
        this.rootDir = resolveInitialRootDir();
        this.segmentSec = effectiveSegmentSec();
        this.retentionDays = effectiveRetentionDays();
        ensureDir(rootDir);
    }

    /** 启动时录像目录：Web 自定义设置优先，否则用配置文件默认值（相对路径相对运行目录）。 */
    private File resolveInitialRootDir() {
        String custom = settingsStore.recordingDir();
        String base = (custom != null && !custom.isBlank()) ? custom : props.getRecordingDir();
        return new File(base).getAbsoluteFile();
    }

    /** 生效的分段秒数：Web 自定义设置优先，否则用配置文件默认值。 */
    private long effectiveSegmentSec() {
        Integer custom = settingsStore.recordSegmentSec();
        return (custom != null && custom > 0) ? custom : props.getRecordSegmentSec();
    }

    /** 生效的保留天数：Web 自定义设置优先，否则配置文件默认（0=永久）。 */
    private int effectiveRetentionDays() {
        Integer custom = settingsStore.recordRetentionDays();
        return (custom != null && custom > 0) ? custom : props.getRecordRetentionDays();
    }

    /** 确保目录存在（启动期失败仅告警，不阻断启动；运行时切换的严格校验见 {@link #validateWritable}）。 */
    private void ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("录像目录创建失败：{}", dir.getAbsolutePath());
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
        long seg = segmentSec == null || segmentSec <= 0 ? this.segmentSec : segmentSec;
        attach(did, device, seg);
        // 记录“期望持续录像”：此后即便服务重启 / 崩溃，也会在启动后自动恢复（显式停止才移除）。
        stateStore.enable(did, seg);
        log.info("[{}] Web 触发录像开始（{}s/段，目录 {}）", did, seg, rootDir.getAbsolutePath());
        return Map.of("did", did, "recording", true, "segmentSec", seg);
    }

    /** 用当前 {@link #rootDir} 为摄像头建立录像订阅（不碰持续录像状态），供 start 与切目录重连复用。 */
    private void attach(String did, CameraDevice device, long seg) {
        RecordingSink sink = new RecordingSink(did, rootDir, seg);
        hub.subscribe(did, device.model(), connector.openerFor(device), sink);
        active.put(did, sink);
    }

    public Map<String, Object> stop(String did) {
        RecordingSink sink = detach(did);
        if (sink == null) {
            throw ApiException.badRequest("该摄像头当前未在录像");
        }
        // 仅“显式停止”才取消持续录像标记；进程关闭 / 崩溃 / 切换目录不走这里，重启后仍会恢复。
        stateStore.disable(did);
        log.info("[{}] Web 停止录像（视频包={} 分段={}）", did, sink.videoPackets(), sink.segmentCount());
        return Map.of("did", did, "recording", false,
                "videoPackets", sink.videoPackets(), "segments", sink.segmentCount());
    }

    /** 解除某摄像头的录像订阅并正常封段（不碰持续录像状态），返回被移除的 sink；未在录返回 null。 */
    private RecordingSink detach(String did) {
        RecordingSink sink = active.remove(did);
        if (sink == null) {
            return null;
        }
        hub.unsubscribe(did, sink);
        sink.close();
        return sink;
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

    // ===================== 存储目录 / 分段设置（Web 设置栏） =====================

    /**
     * 当前录像相关设置信息，供“设置”页展示（RTSP 端口由 Controller 结合 {@code RtspServer} 补充）。
     */
    public Map<String, Object> settingsInfo() {
        File dir = rootDir;
        long count = 0;
        File[] fs = dir.listFiles((d, name) ->
                name.endsWith(".mp4") && FILE_PATTERN.matcher(name).matches());
        if (fs != null) {
            count = fs.length;
        }
        Integer segCustom = settingsStore.recordSegmentSec();
        String dirCustom = settingsStore.recordingDir();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recordingDir", dir.getAbsolutePath());
        m.put("dirCustomized", dirCustom != null && !dirCustom.isBlank());
        m.put("defaultDir", new File(props.getRecordingDir()).getAbsolutePath());
        m.put("segmentSec", segmentSec);
        m.put("defaultSegmentSec", props.getRecordSegmentSec());
        m.put("segmentCustomized", segCustom != null && segCustom > 0);
        Integer retCustom = settingsStore.recordRetentionDays();
        m.put("retentionDays", retentionDays);
        m.put("defaultRetentionDays", props.getRecordRetentionDays());
        m.put("retentionCustomized", retCustom != null && retCustom > 0);
        m.put("recordingCount", count);
        m.put("activeCount", active.size());
        m.put("freeSpace", dir.exists() ? dir.getFreeSpace() : 0L);
        m.put("totalSpace", dir.exists() ? dir.getTotalSpace() : 0L);
        m.put("dataDir", DataDir.get().toString());
        return m;
    }

    /**
     * 运行时切换录像存储目录。
     *
     * <p>rawPath 为 null / 空白表示恢复配置文件默认目录；否则为目录选择器选定的绝对路径（相对路径
     * 相对程序启动目录，与默认 {@code recordings} 行为一致），统一规范化为绝对路径。目录必须可创建、
     * 可写。切换时正在录像的摄像头会先在旧目录正常封段（持续录像标记保留），随后在新目录重新订阅
     * 继续录像；历史文件不会被移动。设置持久化到 settings.json，重启后仍然生效。</p>
     */
    public synchronized Map<String, Object> updateRecordingDir(String rawPath) {
        boolean reset = rawPath == null || rawPath.trim().isEmpty();
        File target = reset ? new File(props.getRecordingDir()) : new File(rawPath.trim());
        File abs;
        try {
            abs = target.getAbsoluteFile().getCanonicalFile();
        } catch (IOException e) {
            throw ApiException.badRequest("路径无效：" + e.getMessage());
        }
        validateWritable(abs);

        File current;
        try {
            current = rootDir.getCanonicalFile();
        } catch (IOException e) {
            current = rootDir.getAbsoluteFile();
        }
        if (abs.equals(current)) {
            // 目录相同：仍把偏好落盘（例如把相对路径确认为当前绝对路径），但不重接录像订阅
            settingsStore.saveRecordingDir(reset ? null : abs.getAbsolutePath());
            return settingsInfo();
        }

        // 先在旧目录对所有正在录像的路正常封段并解除订阅（不取消持续录像标记）
        List<String> running = detachAll();
        // 切换根目录并持久化（先确保目录可用，再落盘，避免保存一个不可用路径）
        this.rootDir = abs;
        ensureDir(abs);
        settingsStore.saveRecordingDir(reset ? null : abs.getAbsolutePath());
        log.info("录像存储目录已切换为：{}（{} 路录像将在新目录继续）", abs.getAbsolutePath(), running.size());
        // 用当前目录与分段重建订阅；设备暂不可达不阻断，交由 reconcile 下一轮用新目录补录
        reattachAll(running);
        return settingsInfo();
    }

    /**
     * 运行时修改录像分段时长（秒）。
     *
     * <p>校验范围后持久化，并让正在录像的路在当前段封段后按新时长续录；同时更新每一路持续录像
     * 状态中记录的段时长，保证重启恢复也使用新值。设置持久化，重启后生效。</p>
     */
    public synchronized Map<String, Object> updateSegmentSec(long sec) {
        if (sec < 10 || sec > 3600) {
            throw ApiException.badRequest("分段时长需在 10～3600 秒之间");
        }
        // 等于配置文件默认值时清除自定义标记（恢复默认）
        Integer saved = (sec == props.getRecordSegmentSec()) ? null : Integer.valueOf((int) sec);
        if (sec == this.segmentSec) {
            settingsStore.saveRecordSegmentSec(saved);
            return settingsInfo();
        }
        // 同步更新每一路持久化期望状态里的段时长，否则重建 / 重启恢复仍会取旧值
        for (String did : stateStore.cameras().keySet()) {
            stateStore.enable(did, sec);
        }
        List<String> running = detachAll();
        this.segmentSec = sec;
        settingsStore.saveRecordSegmentSec(saved);
        log.info("录像分段时长已改为 {}s（{} 路录像将按新时长续录）", sec, running.size());
        reattachAll(running);
        return settingsInfo();
    }

    /**
     * 修改录像保留天数（0=永久保留）。持久化后立即按新策略清理一次。
     *
     * <p>保留天数不需要重建录像通道，仅更新策略并触发一次清理。</p>
     */
    public synchronized Map<String, Object> updateRetentionDays(int days) {
        if (days < 0 || days > 3650) {
            throw ApiException.badRequest("保留天数需在 0～3650 之间（0 表示永久保留）");
        }
        this.retentionDays = days;
        settingsStore.saveRecordRetentionDays(days > 0 ? days : null);
        int removed = cleanupExpired();
        log.info("录像保留天数已设为 {}（{}），本次清理过期分段 {} 个",
                days, days > 0 ? "保留最近 " + days + " 天" : "永久保留", removed);
        return settingsInfo();
    }

    /**
     * 删除超出保留天数的录像分段。
     *
     * <p>判定基准为文件最后修改时间（已封段即封段时间），仅清理符合命名规则的 mp4；
     * 保留天数为 0（永久）时不清理；任何正在写入的分段都不删除。返回删除的文件数量。</p>
     */
    public int cleanupExpired() {
        int days = retentionDays;
        if (days <= 0) {
            return 0;
        }
        File dir = rootDir;
        File[] files = dir.listFiles((d, name) ->
                name.endsWith(".mp4") && FILE_PATTERN.matcher(name).matches());
        if (files == null || files.length == 0) {
            return 0;
        }
        // 所有正在写入的分段名一律不删（它们的时间戳也很新，双重保险）。
        Set<String> writing = new HashSet<>();
        active.forEach((did, sink) -> {
            String n = sink.currentSegmentName();
            if (n != null) {
                writing.add(n);
            }
        });
        long cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000;
        int removed = 0;
        for (File f : files) {
            try {
                if (writing.contains(f.getName()) || f.lastModified() >= cutoff) {
                    continue;
                }
                if (Files.deleteIfExists(f.toPath())) {
                    removed++;
                    log.info("清理过期录像（保留 {} 天）：{}", days, f.getName());
                }
            } catch (IOException e) {
                log.warn("清理录像失败 {}：{}", f.getName(), e.getMessage());
            }
        }
        if (removed > 0) {
            log.info("录像保留清理完成：共删除 {} 个过期分段", removed);
        }
        return removed;
    }

    /** 解除全部活动录像订阅（在旧目录 / 旧参数下正常封段），返回被拆除的 did 快照。 */
    private List<String> detachAll() {
        List<String> dids = new ArrayList<>(active.keySet());
        for (String did : dids) {
            if (detach(did) != null) {
                log.info("[{}] 设置变更：当前段已正常封段", did);
            }
        }
        return dids;
    }

    /** 用当前 {@link #rootDir} 与各路段时长（持久化期望状态，缺省取生效值）重建订阅。 */
    private void reattachAll(List<String> dids) {
        Map<String, Long> segMap = stateStore.cameras();
        for (String did : dids) {
            try {
                // 极罕见竞态：detach 后 reconcile 已抢先重建该路，则不重复订阅
                if (active.containsKey(did)) {
                    continue;
                }
                CameraDevice device = connector.findDevice(did);
                if (device == null) {
                    log.warn("[{}] 设置变更后未在设备列表找到，等待对账恢复录像", did);
                    continue;
                }
                long seg = segMap.getOrDefault(did, segmentSec);
                attach(did, device, seg);
                log.info("[{}] 已按新设置继续录像", did);
            } catch (Exception ex) {
                log.warn("[{}] 设置变更后重接录像失败，等待对账恢复：{}", did, ex.toString());
            }
        }
    }

    /** 校验目标目录存在且可写（创建 + 临时探测文件），不可用则抛 400 并带可读原因。 */
    private void validateWritable(File dir) {
        try {
            Files.createDirectories(dir.toPath());
        } catch (IOException e) {
            throw ApiException.badRequest("无法创建目录 " + dir.getAbsolutePath() + "：" + e.getMessage());
        }
        if (!dir.isDirectory()) {
            throw ApiException.badRequest("路径不是目录：" + dir.getAbsolutePath());
        }
        File probe = null;
        try {
            probe = File.createTempFile("invr-wtest-", ".tmp", dir);
        } catch (IOException e) {
            throw ApiException.badRequest("目录不可写 " + dir.getAbsolutePath() + "：" + e.getMessage());
        } finally {
            if (probe != null && !probe.delete()) {
                probe.deleteOnExit();
            }
        }
    }
}
