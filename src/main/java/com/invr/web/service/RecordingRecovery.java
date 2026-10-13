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
 * 持续录像自愈（期望状态 → 实际订阅的对账）。
 *
 * <p>用户点过“开始录像”的摄像头会被持久化到数据目录下的 {@code recordings.json}
 *（默认运行目录 {@code .invr/}，见 {@link com.invr.store.DataDir}）。本组件在
 * 应用就绪后及之后周期性地调用 {@link RecordingService#reconcile()}，把“标记要录、当前却没在录”
 * 的摄像头重新拉起——因此服务重启、崩溃恢复、甚至启动时米家会话尚未就绪（稍后登录）都会自动
 * 继续录像，无需人工再点一次。仅在已登录米家账号时对账；任何单次异常都不影响后续周期。</p>
 */
@Component
public class RecordingRecovery {

    private static final Logger log = LoggerFactory.getLogger(RecordingRecovery.class);

    /** 首次对账延迟：给会话恢复 / 容器初始化留出时间。 */
    private static final long INITIAL_DELAY_SECONDS = 3;
    /** 对账周期：同时覆盖“启动后恢复”和“登录后恢复”，以及设备暂时不可达后的重试。 */
    private static final long PERIOD_SECONDS = 20;

    private final RecordingService recordings;
    private final SessionHolder sessions;
    private final ScheduledExecutorService executor;

    public RecordingRecovery(RecordingService recordings, SessionHolder sessions) {
        this.recordings = recordings;
        this.sessions = sessions;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "recording-recovery");
            t.setDaemon(true);
            return t;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        executor.scheduleWithFixedDelay(this::tick,
                INITIAL_DELAY_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
        log.info("持续录像对账已启用（每 {}s）", PERIOD_SECONDS);
    }

    private void tick() {
        try {
            if (!sessions.isLoggedIn()) {
                return;
            }
            int restored = recordings.reconcile();
            if (restored > 0) {
                log.info("对账恢复了 {} 路持续录像", restored);
            }
        } catch (Throwable t) {
            // 定时任务里绝不让异常逃逸，否则 ScheduledExecutor 会取消后续所有周期。
            log.debug("持续录像对账本轮失败，下轮重试：{}", t.toString());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
