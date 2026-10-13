package com.invr.web.service;

import com.invr.cloud.DeviceDiscovery;
import com.invr.cloud.MiCloudApi;
import com.invr.cloud.MiCloudUnauthorizedException;
import com.invr.model.CameraDevice;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 当前账号下摄像头目录（Web 单例）。登录/手动刷新时从米家云拉取并缓存，
 * 供 RTSP / HLS / 录像按 DID 解析设备（model、局域网 IP、在线状态）。
 */
@Component
public class DeviceCatalog {

    private static final Logger log = LoggerFactory.getLogger(DeviceCatalog.class);

    /** 后台自动刷新间隔（秒）：用于让在线/离线状态自动更新，不必等手动刷新。 */
    private static final long AUTO_REFRESH_SECONDS = 60;

    private final SessionHolder sessionHolder;
    private volatile List<CameraDevice> devices = List.of();
    private final ScheduledExecutorService scheduler;

    public DeviceCatalog(SessionHolder sessionHolder) {
        this.sessionHolder = sessionHolder;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "device-catalog-refresh");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::safeRefresh,
                AUTO_REFRESH_SECONDS, AUTO_REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    /** 后台任务：仅在已登录时刷新，任何异常都静默保留上一份缓存。 */
    private void safeRefresh() {
        try {
            if (sessionHolder.isLoggedIn() && !devices.isEmpty()) {
                refresh();
            }
        } catch (Exception e) {
            log.debug("后台刷新设备列表失败，保留旧缓存：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /** 强制从云端刷新设备列表；401 时自动续期并重试一次。 */
    public List<CameraDevice> refresh() {
        var session = sessionHolder.require();
        try {
            devices = new DeviceDiscovery(new MiCloudApi(session)).listCameras();
        } catch (MiCloudUnauthorizedException e) {
            log.info("设备列表会话过期，自动续期后重试");
            var renewed = sessionHolder.renew();
            devices = new DeviceDiscovery(new MiCloudApi(renewed)).listCameras();
        }
        return devices;
    }

    public List<CameraDevice> all() {
        return devices;
    }

    public Optional<CameraDevice> find(String did) {
        return devices.stream().filter(c -> c.did().equals(did)).findFirst();
    }

    /** 找到则返回，否则刷新一次再找（处理刚登录尚未拉列表的情况）。 */
    public CameraDevice findOrRefresh(String did) {
        return find(did).orElseGet(() ->
                refresh().stream().filter(c -> c.did().equals(did)).findFirst().orElse(null));
    }
}
