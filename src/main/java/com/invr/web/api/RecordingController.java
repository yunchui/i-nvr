package com.invr.web.api;

import com.invr.web.service.RecordingService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * MP4 分段录像的启停、列表、在线回放 / 下载、删除。
 */
@RestController
@RequestMapping("/api")
public class RecordingController {

    private final RecordingService recordingService;

    public RecordingController(RecordingService recordingService) {
        this.recordingService = recordingService;
    }

    public record StartRequest(Long segmentSec) {
    }

    @PostMapping("/record/{did}/start")
    public Map<String, Object> start(@PathVariable String did,
                                     @RequestBody(required = false) StartRequest req) {
        Long seg = req == null ? null : req.segmentSec();
        return recordingService.start(did, seg);
    }

    @PostMapping("/record/{did}/stop")
    public Map<String, Object> stop(@PathVariable String did) {
        return recordingService.stop(did);
    }

    @GetMapping("/recordings")
    public List<Map<String, Object>> list(@RequestParam(name = "did", required = false) String did)
            throws IOException {
        return recordingService.list(did);
    }

    /** 时间轴回放：某摄像头某天的全部分段（升序，含开始时间/估算时长/是否录制中）。 */
    @GetMapping("/timeline/{did}")
    public Map<String, Object> timeline(@PathVariable String did,
                                        @RequestParam(name = "date", required = false) String date)
            throws IOException {
        return recordingService.timeline(did, date);
    }

    @GetMapping("/recordings/{name:.+}")
    public ResponseEntity<Resource> playback(@PathVariable String name,
                                             @RequestParam(name = "dl", defaultValue = "false") boolean download) {
        File f = recordingService.resolveFile(name);
        String disposition = (download ? "attachment" : "inline")
                + "; filename*=UTF-8''" + URLEncoder.encode(f.getName(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(new FileSystemResource(f));
    }

    @DeleteMapping("/recordings/{name:.+}")
    public Map<String, Object> delete(@PathVariable String name) {
        recordingService.delete(name);
        return Map.of("deleted", name);
    }
}
