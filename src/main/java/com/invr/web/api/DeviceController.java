package com.invr.web.api;

import com.invr.model.CameraDevice;
import com.invr.web.config.NvrProperties;
import com.invr.web.service.DeviceCatalog;
import com.invr.web.service.HlsService;
import com.invr.web.service.RecordingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 摄像头设备列表与实时状态（预览 / 录像 / 下行地址）。
 */
@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceCatalog catalog;
    private final RecordingService recordingService;
    private final HlsService hlsService;
    private final NvrProperties props;

    public DeviceController(DeviceCatalog catalog, RecordingService recordingService,
                            HlsService hlsService, NvrProperties props) {
        this.catalog = catalog;
        this.recordingService = recordingService;
        this.hlsService = hlsService;
        this.props = props;
    }

    @GetMapping("/devices")
    public Map<String, Object> devices(@RequestParam(name = "refresh", defaultValue = "false") boolean refresh,
                                       HttpServletRequest request) {
        List<CameraDevice> devices = (refresh || catalog.all().isEmpty())
                ? catalog.refresh() : catalog.all();
        String host = request.getServerName();
        List<Map<String, Object>> list = devices.stream()
                .map(c -> toMap(c, host))
                .toList();
        return Map.of("devices", list, "count", list.size());
    }

    @GetMapping("/devices/{did}")
    public Map<String, Object> device(@PathVariable String did, HttpServletRequest request) {
        CameraDevice c = catalog.findOrRefresh(did);
        if (c == null) {
            throw ApiException.notFound("设备列表中未找到 did=" + did);
        }
        return toMap(c, request.getServerName());
    }

    private Map<String, Object> toMap(CameraDevice c, String host) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("did", c.did());
        m.put("name", c.name());
        m.put("model", c.model());
        m.put("localIp", c.localIp());
        m.put("online", c.online());
        m.put("recording", recordingService.isRecording(c.did()));
        m.put("previewing", hlsService.isPreviewing(c.did()));
        m.put("rtspUrl", "rtsp://" + host + ":" + props.getRtspPort() + "/" + c.did());
        m.put("hlsUrl", "/hls/" + c.did() + "/index.m3u8");
        return m;
    }
}
