package com.invr.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.invr.model.CameraDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 设备发现：拉取米家设备列表并过滤摄像头类设备。
 */
public class DeviceDiscovery {

    private static final Logger log = LoggerFactory.getLogger(DeviceDiscovery.class);

    private static final String API_DEVICE_LIST = "/v2/home/device_list_page";
    private static final String API_RPC = "/home/rpc/";

    private final MiCloudApi api;

    public DeviceDiscovery(MiCloudApi api) {
        this.api = api;
    }

    /**
     * 列出账号下所有摄像头（.camera. / .cateye. / .feeder.）。
     */
    public List<CameraDevice> listCameras() {
        JsonNode result = api.call(API_DEVICE_LIST, Map.of());
        List<CameraDevice> cameras = new ArrayList<>();
        int total = 0;
        int onlineCount = 0;
        for (JsonNode device : result.path("list")) {
            total++;
            String model = device.path("model").asText("");
            if (isCameraModel(model)) {
                boolean online = device.path("isOnline").asBoolean(false);
                if (online) {
                    onlineCount++;
                }
                cameras.add(new CameraDevice(
                        device.path("did").asText(""),
                        device.path("name").asText(""),
                        model,
                        device.path("mac").asText(""),
                        device.path("localip").asText(""),
                        online));
            }
        }
        log.info("设备发现：账号下共 {} 台设备，其中摄像头 {} 台（在线 {} 台）", total, cameras.size(), onlineCount);
        return cameras;
    }

    /**
     * 门铃 / 电池类摄像头（.cateye.）取流前先唤醒。
     */
    public void wakeup(String did) {
        Map<String, Object> params = Map.of(
                "id", 1,
                "method", "wakeup",
                "params", Map.of("video", "1"));
        api.call(API_RPC + did, params);
    }

    static boolean isCameraModel(String model) {
        return model.contains(".camera.") || model.contains(".cateye.") || model.contains(".feeder.");
    }
}
