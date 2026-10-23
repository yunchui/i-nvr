package com.invr.web.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 录像过期清理（保留天数策略）。
 *
 * <p>应用就绪后周期性地调用 {@link RecordingService#cleanupExpired()}，删除超出“录像保留天数”
 * （设置页可配，0=永久保留）的分段。保留天数为 0 时清理逻辑直接返回，不做任何删除。
 * 这是纯本地文件维护，与米家登录状态无关；任何单次异常都不影响后续周期。</p>
 */
@Component
public class RecordingCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(RecordingCleanupTask.class);

    /** 首次清理延迟：等待录像目录与设置就绪。 */
    private static final long INITIAL_DELAY_SECONDS = 60;
    /** 清理周期：每小时巡检一次（按天判断，多跑无害）。 */
    private static final long PERIOD_SECONDS = 3600;

    private final RecordingService recordings;
    private final ScheduledExecutorService executor;

    public RecordingCleanupTask(RecordingService recordings) {
        this.recordings = recordings;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "recording-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        executor.scheduleWithFixedDelay(this::tick,
                INITIAL_DELAY_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
        log.info("录像过期清理已启用（每 {}s 巡检一次）", PERIOD_SECONDS);
    }

    private void tick() {
        try {
            recordings.cleanupExpired();
        } catch (Throwable t) {
            // 定时任务里绝不让异常逃逸，否则 ScheduledExecutor 会取消后续所有周期。
            log.debug("录像清理本轮失败，下轮重试：{}", t.toString());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
