package com.invr.wall.board;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 看板配置轮询同步器：首次立即拉取，之后按服务端下发的 configPollSec 周期 reconcile。
 *
 * <p>与 Web 端 share.js 语义一致：轮询失败保留现状下个周期再试；410 立即上抛致命态并停轮询。
 * 周期允许每次响应后动态调整（设置页运行时改过轮询间隔，下一轮生效）。</p>
 */
public final class BoardSyncer {

    private static final Logger log = LoggerFactory.getLogger(BoardSyncer.class);
    private static final long MIN_POLL_SEC = 5L;

    /** 同步事件回调，全部在同步器工作线程触发，UI 层自行切 FX 线程。 */
    public interface Listener {
        void onBoard(BoardDto board);

        void onGone();

        void onError(Exception e);
    }

    private final BoardClient client;
    private final Listener listener;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicLong periodSec = new AtomicLong(60L);

    public BoardSyncer(BoardClient client, Listener listener) {
        this.client = client;
        this.listener = listener;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wall-board-sync");
            t.setDaemon(true);
            return t;
        });
    }

    /** 启动：立即拉一次，随后自调度固定延迟轮询。 */
    public void start() {
        executor.schedule(this::pollOnce, 0, TimeUnit.SECONDS);
    }

    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private void scheduleNext() {
        if (!stopped.get()) {
            executor.schedule(this::pollOnce, periodSec.get(), TimeUnit.SECONDS);
        }
    }

    private void pollOnce() {
        if (stopped.get()) {
            return;
        }
        try {
            BoardDto board = client.fetchBoard();
            long p = Math.max(MIN_POLL_SEC, board.configPollSec);
            periodSec.set(p);
            listener.onBoard(board);
        } catch (BoardGoneException e) {
            log.warn("看板已失效：{}", e.getMessage());
            listener.onGone();
            stop();
            return;
        } catch (Exception e) {
            log.warn("看板轮询失败，保留现状：{}", e.toString());
            listener.onError(e);
        }
        scheduleNext();
    }
}
