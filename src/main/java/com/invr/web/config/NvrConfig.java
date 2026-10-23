package com.invr.web.config;

import com.invr.hub.StreamHub;
import com.invr.hub.CameraConnector;
import com.invr.rtsp.RtspServer;
import com.invr.store.AppSettingsStore;
import com.invr.store.RecordingStateStore;
import com.invr.store.SessionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 核心单例装配。{@link StreamHub} 为进程级单例，是"同一摄像头只占一条上游"硬约束在
 * Web 架构下的落点：RTSP、HLS 预览、MP4 录像全部订阅这同一个实例。
 */
@Configuration
public class NvrConfig {

    @Bean
    public SessionStore sessionStore() {
        return new SessionStore();
    }

    @Bean
    public RecordingStateStore recordingStateStore() {
        return new RecordingStateStore();
    }

    @Bean
    public AppSettingsStore appSettingsStore() {
        return new AppSettingsStore();
    }

    @Bean(destroyMethod = "shutdown")
    public StreamHub streamHub() {
        return new StreamHub();
    }

    @Bean
    public RtspServer rtspServer(NvrProperties props, StreamHub hub, CameraConnector connector,
                                 AppSettingsStore appSettingsStore) {
        // 启动端口：Web 设置（settings.json）优先，缺省回落配置文件 invr.rtsp-port
        Integer custom = appSettingsStore.rtspPort();
        int port = (custom != null && custom > 0) ? custom : props.getRtspPort();
        return new RtspServer(port, hub, connector);
    }
}
