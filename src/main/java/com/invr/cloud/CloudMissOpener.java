package com.invr.cloud;

import com.invr.hub.MissOpener;
import com.invr.miss.MissClient;
import com.invr.model.CameraDevice;
import com.invr.model.P2pConnectParams;
import com.invr.store.SessionStore;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 小米云端实现的上游开启器：每次（重）连接都重新 miss_get_vendor 协商，
 * 再建立 CS2/MISS 并 VideoStart。供 StreamHub 的自动重连循环调用。
 *
 * <p>协商遇到会话过期（401）时用 passToken 自动续期一次并重试；
 * 门铃/电池类（.cateye.）连接前先唤醒。</p>
 */
public final class CloudMissOpener implements MissOpener {

    private final SessionStore sessionStore;
    private final AtomicReference<LoginSession> sessionRef;
    private final CameraDevice device;
    private final String channel;
    private final String quality;
    private final String audio;

    public CloudMissOpener(SessionStore sessionStore,
                           AtomicReference<LoginSession> sessionRef,
                           CameraDevice device,
                           String channel,
                           String quality,
                           String audio) {
        this.sessionStore = sessionStore;
        this.sessionRef = sessionRef;
        this.device = device;
        this.channel = channel == null ? "" : channel;
        this.quality = quality == null ? "hd" : quality;
        this.audio = audio == null ? "1" : audio;
    }

    @Override
    public MissClient open() throws IOException {
        P2pConnectParams params = negotiateWithRecovery();

        if (device.model() != null && device.model().contains(".cateye.")) {
            try {
                new DeviceDiscovery(new MiCloudApi(sessionRef.get())).wakeup(device.did());
                Thread.sleep(1_000);
            } catch (Exception e) {
                // 唤醒失败不阻断，继续尝试取流
            }
        }

        MissClient client = MissClient.connect(params, "");
        boolean ok = false;
        try {
            client.startMedia(channel, quality, audio);
            ok = true;
            return client;
        } finally {
            if (!ok) {
                client.close();
            }
        }
    }

    private P2pConnectParams negotiateWithRecovery() {
        try {
            return new VendorNegotiator(new MiCloudApi(sessionRef.get())).negotiate(device);
        } catch (MiCloudUnauthorizedException e) {
            LoginSession old = sessionRef.get();
            LoginSession renewed = new MiCloudClientImpl()
                    .loginWithToken(old.userId(), old.passToken(), old.region());
            sessionStore.save(renewed);
            sessionRef.set(renewed);
            return new VendorNegotiator(new MiCloudApi(renewed)).negotiate(device);
        }
    }
}
