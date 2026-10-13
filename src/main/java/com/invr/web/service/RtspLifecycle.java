package com.invr.web.service;

import com.invr.rtsp.RtspServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 随 Web 进程启动/停止内嵌 RTSP 服务，与 HLS/录像共享同一 StreamHub。
 */
@Component
public class RtspLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RtspLifecycle.class);

    private final RtspServer rtspServer;

    public RtspLifecycle(RtspServer rtspServer) {
        this.rtspServer = rtspServer;
    }

    @PostConstruct
    public void start() {
        try {
            rtspServer.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RTSP 服务启动被中断", e);
        }
    }

    @PreDestroy
    public void stop() {
        log.info("正在关闭内嵌 RTSP 服务...");
        rtspServer.stop();
    }
}
