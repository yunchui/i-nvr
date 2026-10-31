package com.invr.web.api;

import com.invr.rtsp.RtspServer;
import com.invr.store.AccessKeyStore;
import com.invr.store.AppSettingsStore;
import com.invr.web.config.NvrProperties;
import com.invr.web.service.HlsService;
import com.invr.web.service.RecordingService;
import com.invr.web.service.ShareEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置：录像存储目录、录像分段时长、RTSP 端口，以及服务端目录浏览（供 Web 目录选择器使用）。
 * 均位于 {@code /api/**}，受访问密码门禁保护。
 */
@RestController
@RequestMapping("/api")
public class SettingsController {

    private final RecordingService recordingService;
    private final RtspServer rtspServer;
    private final NvrProperties props;
    private final AppSettingsStore settingsStore;
    private final AccessKeyStore accessKeyStore;
    private final HlsService hlsService;
    private final ShareEventService shareEventService;

    public SettingsController(RecordingService recordingService, RtspServer rtspServer,
                              NvrProperties props, AppSettingsStore settingsStore,
                              AccessKeyStore accessKeyStore,
                              HlsService hlsService, ShareEventService shareEventService) {
        this.recordingService = recordingService;
        this.rtspServer = rtspServer;
        this.props = props;
        this.settingsStore = settingsStore;
        this.accessKeyStore = accessKeyStore;
        this.hlsService = hlsService;
        this.shareEventService = shareEventService;
    }

    /** 聚合返回全部设置（录像相关 + 实时 RTSP 端口）。 */
    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        Map<String, Object> m = recordingService.settingsInfo();
        m.put("rtspPort", rtspServer.getPort());
        m.put("defaultRtspPort", props.getRtspPort());
        m.put("rtspCustomized", settingsStore.rtspPort() != null);
        m.put("hls", hlsService.hlsSettingsInfo());
        m.put("share", shareEventService.shareSettingsInfo());
        return m;
    }

    /** 修改录像存储目录（recordingDir 为空表示恢复默认；由目录选择器选定绝对路径）。 */
    @PutMapping("/settings")
    public Map<String, Object> update(@RequestBody(required = false) SettingsRequest req) {
        recordingService.updateRecordingDir(req == null ? null : req.recordingDir());
        return getSettings();
    }

    /** 修改录像分段时长（秒，10～3600；等于配置默认值时恢复默认）。 */
    @PutMapping("/settings/segment")
    public Map<String, Object> updateSegment(@RequestBody(required = false) SegmentRequest req) {
        long sec = req == null ? -1L : req.segmentSec();
        recordingService.updateSegmentSec(sec);
        return getSettings();
    }

    /** 修改 RTSP 端口并立即重绑定；绑定失败时保留旧端口，且不落盘。 */
    @PutMapping("/settings/rtsp")
    public Map<String, Object> updateRtsp(@RequestBody(required = false) RtspRequest req) {
        int port = req == null ? -1 : req.rtspPort();
        if (port < 1024 || port > 65535) {
            throw ApiException.badRequest("RTSP 端口需在 1024～65535 之间");
        }
        try {
            // 先在新端口绑定成功（rebind 内部保证失败时旧服务仍可用），再持久化
            rtspServer.rebind(port);
        } catch (IOException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        settingsStore.saveRtspPort(port == props.getRtspPort() ? null : port);
        return getSettings();
    }

    /** 修改录像保留天数（0=永久保留，>0 时超出该天数的分段自动删除）。 */
    @PutMapping("/settings/retention")
    public Map<String, Object> updateRetention(@RequestBody(required = false) RetentionRequest req) {
        int days = req == null ? -1 : req.retentionDays();
        return recordingService.updateRetentionDays(days);
    }

    /** 修改 HLS 预览参数（分片时长/窗口/空闲/音频/预热），运行时生效并持久化。 */
    @PutMapping("/settings/hls")
    public Map<String, Object> updateHls(@RequestBody(required = false) HlsSettingsRequest req) {
        if (req == null) {
            throw ApiException.badRequest("缺少请求体");
        }
        hlsService.updateHls(req.targetSec(), req.window(), req.idleSec(), req.audio(), req.prewarm());
        return getSettings();
    }

    /** 修改共享看板参数（事件时长/心跳/兜底重载/配置轮询），运行时生效并持久化。 */
    @PutMapping("/settings/share")
    public Map<String, Object> updateShare(@RequestBody(required = false) ShareSettingsRequest req) {
        if (req == null) {
            throw ApiException.badRequest("缺少请求体");
        }
        shareEventService.updateShare(req.eventDurationSec(), req.sseHeartbeatSec(),
                req.pageReloadSec(), req.configPollSec());
        return getSettings();
    }

    /** 修改门禁访问密码（已在受保护会话内，需校验当前密码；成功后返回新的一键解锁路径）。 */
    @PutMapping("/settings/password")
    public Map<String, Object> changePassword(@RequestBody(required = false) PasswordChangeRequest req) {
        if (req == null) {
            throw ApiException.badRequest("缺少请求体");
        }
        char[] oldPw = req.oldPassword() == null ? null : req.oldPassword().toCharArray();
        char[] newPw = req.newPassword() == null ? null : req.newPassword().toCharArray();
        String token;
        try {
            token = accessKeyStore.changePassword(oldPw, newPw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("unlockPath", "/unlock?key=" + token);
        return m;
    }

    /**
     * 服务端目录浏览：仅返回一级子目录（供弹窗目录选择器逐级展开）。
     *
     * <p>path 为空时返回文件系统根（Windows 盘符 / Linux /）；否则返回该目录下的子目录，
     * 每个条目带 {@code writable} 标识（仅可写目录允许最终选择）。浏览器无法直接访问服务端
     * 文件系统，故由该接口代为浏览。</p>
     */
    @GetMapping("/settings/browse")
    public Map<String, Object> browse(@RequestParam(name = "path", required = false) String path) {
        Map<String, Object> resp = new LinkedHashMap<>();
        File[] rootFiles = File.listRoots();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (File r : rootFiles) {
            roots.add(entryOf(r, r.getPath()));
        }
        resp.put("roots", roots);

        // 未指定路径：展示文件系统根（盘符）
        if (path == null || path.trim().isEmpty()) {
            resp.put("current", null);
            resp.put("parent", null);
            resp.put("entries", roots);
            resp.put("unreadable", false);
            return resp;
        }

        File dir;
        try {
            dir = new File(path.trim()).getAbsoluteFile().getCanonicalFile();
        } catch (IOException e) {
            throw ApiException.badRequest("路径无效：" + e.getMessage());
        }
        if (!dir.exists() || !dir.isDirectory()) {
            throw ApiException.badRequest("不是有效的目录：" + dir.getAbsolutePath());
        }

        boolean isRoot = false;
        for (File r : rootFiles) {
            try {
                if (r.getCanonicalFile().equals(dir)) {
                    isRoot = true;
                    break;
                }
            } catch (IOException ignore) {
                // 比较盘符失败时按非根处理
            }
        }
        File parent = dir.getParentFile();
        resp.put("current", dir.getAbsolutePath());
        resp.put("parent", (!isRoot && parent != null) ? parent.getAbsolutePath() : null);

        File[] kids = null;
        try {
            kids = dir.listFiles(File::isDirectory);
        } catch (SecurityException ignore) {
            // 无权限列出时按空处理
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        if (kids != null) {
            Arrays.sort(kids, Comparator.comparing((File f) -> f.getName().toLowerCase()));
            for (File k : kids) {
                entries.add(entryOf(k, k.getName()));
            }
        }
        resp.put("entries", entries);
        resp.put("unreadable", kids == null);
        return resp;
    }

    /** 组装一个目录条目：名称、绝对路径、是否可写。 */
    private Map<String, Object> entryOf(File f, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean writable = false;
        try {
            writable = f.isDirectory() && Files.isWritable(f.toPath());
        } catch (Exception ignore) {
            writable = false;
        }
        m.put("name", name);
        m.put("path", f.getAbsolutePath());
        m.put("writable", writable);
        return m;
    }

    public record SettingsRequest(String recordingDir) {
    }

    public record SegmentRequest(long segmentSec) {
    }

    public record RtspRequest(int rtspPort) {
    }

    public record RetentionRequest(int retentionDays) {
    }

    public record PasswordChangeRequest(String oldPassword, String newPassword) {
    }

    public record HlsSettingsRequest(int targetSec, int window, int idleSec,
                                     boolean audio, boolean prewarm) {
    }

    public record ShareSettingsRequest(long eventDurationSec, long sseHeartbeatSec,
                                       long pageReloadSec, long configPollSec) {
    }
}
