package com.invr.web.service;

import com.invr.cloud.CloudMissOpener;
import com.invr.hub.CameraConnector;
import com.invr.hub.MissOpener;
import com.invr.miss.MissCommands;
import com.invr.model.CameraDevice;
import com.invr.store.SessionStore;
import org.springframework.stereotype.Component;

/**
 * 所有下行消费者（RTSP / HLS / 录像）共用的摄像头连接器：
 * 按 DID 解析设备、并为设备构造汇入全局 StreamHub 的上游开启器。
 */
@Component
public class DefaultCameraConnector implements CameraConnector {

    private final SessionStore sessionStore;
    private final SessionHolder sessionHolder;
    private final DeviceCatalog catalog;

    public DefaultCameraConnector(SessionStore sessionStore, SessionHolder sessionHolder,
                                  DeviceCatalog catalog) {
        this.sessionStore = sessionStore;
        this.sessionHolder = sessionHolder;
        this.catalog = catalog;
    }

    @Override
    public CameraDevice findDevice(String did) {
        return catalog.findOrRefresh(did);
    }

    @Override
    public MissOpener openerFor(CameraDevice device) {
        String quality = MissCommands.defaultQuality(device.model());
        return new CloudMissOpener(sessionStore, sessionHolder.ref(), device, "", quality, "1");
    }
}
