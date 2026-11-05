package com.invr.wall.media;

import javafx.application.Platform;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单路 RTSP 播放器：独立 daemon 抓取线程 + FFmpeg 低延迟参数 + swscale 直接输出 BGRA，
 * 按行整块拷贝写入 JavaFX {@link PixelBuffer}（背景线程更新，FX 渲染线程同步上屏）。
 *
 * <p>关键策略：</p>
 * <ul>
 *   <li>强制 RTP-over-TCP（服务端仅支持 interleaved），以 low_delay 等低延迟选项压低稳态延迟；
 *       禁用 fflags=nobuffer（会导致本服务端 H.265 重组缺参考帧、画面整屏灰）；</li>
 *   <li>{@code allowed_media_types=video} 只 SETUP 视频 track，一期静音、忽略任何音频帧；</li>
 *   <li>保留摄像头原始分辨率、native 不缩放，首帧按真实帧尺寸建缓冲；缩放置于 ImageView
 *       （GPU 平滑），清晰度与格子尺寸/窗口大小解耦，网格只按百分比分割屏幕；</li>
 *   <li>看门狗由外部定时器调用 {@link #tickWatchdog(long)}：帧龄 10s 或建连 20s 无首帧即重建；</li>
 *   <li>连续失败 {@link #MAX_FAILS} 次进 ERROR，不再自动重试，交由 reconcile 显式重启，
 *       避免对不存在/不可恢复的路无限刷连接。</li>
 * </ul>
 */
public final class RtspPlayer {

    private static final Logger log = LoggerFactory.getLogger(RtspPlayer.class);

    /** 连续重连失败上限（对齐 Web watchdog 的 maxFails=5）。 */
    public static final int MAX_FAILS = 5;
    /** 看门狗：PLAYING 帧龄超过该值判定冻结（毫秒）。 */
    public static final long STALL_LIMIT_MS = 10_000L;
    /** 看门狗：CONNECTING 超过该值未出首帧判定建连失败（毫秒，DESCRIBE 等参数集最多 4s，放宽到 20s）。 */
    public static final long CONNECT_LIMIT_MS = 20_000L;
    /** 上屏限频间隔（毫秒），约 15fps，足够大屏观看且控制 FX 刷新开销。 */
    private static final long PUSH_INTERVAL_MS = 66L;
    /** 退避基数（毫秒），第 n 次失败等待 base×n。 */
    private static final long BACKOFF_BASE_MS = 5_000L;
    /**
     * 建连后始终拿不到首帧的连续失败上限。达到即判 ERROR，不再对"RTSP 能连上但无视频"的
     * 死路（典型：设备本地 P2P 链路不通，服务端无上游视频包可下发）无限空连，避免其 grabber
     * 反复忙等抢占资源、拖慢同屏其他健康路。ERROR 后仍由 board 轮询 reconcile 与 F5 手动恢复。
     */
    private static final int NO_FRAME_FAIL_LIMIT = 2;
    /** 无首帧失败后的单次退避（毫秒），退避期间不持有 native 连接。 */
    private static final long NO_FRAME_BACKOFF_MS = 5_000L;

    /** 状态变更回调，触发于抓取线程，UI 层需自行切 FX 线程。 */
    public interface Listener {
        default void onStateChanged(RtspPlayer player, PlayerState state) {
        }

        /** WritableImage 因实际帧尺寸变化而重建时回调，UI 需重新绑定 ImageView。 */
        default void onImageChanged(RtspPlayer player) {
        }
    }

    private static final Listener NOOP = new Listener() {
    };

    private final String did;
    private final String rtspUrl;
    private volatile Listener listener;
    private final AtomicLong seq = new AtomicLong();

    private volatile PlayerState state = PlayerState.IDLE;
    private volatile boolean stopped;
    private volatile boolean offline;
    /** 手动刷新标志：消费后跳过失败退避，立即重建连接。 */
    private volatile boolean refreshRequested;
    private volatile FFmpegFrameGrabber grabber;
    private Thread worker;

    private volatile int fails;
    /** 连续"建连后从未出首帧"次数，仅播放工作线程访问。 */
    private int noFrameFails;
    private volatile long lastFrameMs;
    private volatile long connectStartMs;
    private long lastPushMs;
    private volatile long lastWatchdogCloseMs;

    /** 保护 bgraBuffer：工作线程写、FX 渲染线程 updateBuffer 读取。 */
    private final Object bufferLock = new Object();
    private WritableImage image;
    private PixelBuffer<ByteBuffer> pixelBuffer;
    private ByteBuffer bgraBuffer;
    private byte[] rowBuffer;
    private int surfaceW;
    private int surfaceH;

    public RtspPlayer(String did, String rtspUrl, Listener listener) {
        this.did = did;
        this.rtspUrl = rtspUrl;
        this.listener = listener == null ? NOOP : listener;
        // 不在构造期预分配上屏缓冲：保留摄像头原始分辨率，首帧到达后按真实帧尺寸创建
    }

    /** 重新绑定 UI 回调（reconcile 后播放器与槽位重新配对时使用）。 */
    public void setListener(Listener l) {
        this.listener = l == null ? NOOP : l;
    }

    public String did() {
        return did;
    }

    public PlayerState state() {
        return state;
    }

    public WritableImage image() {
        return image;
    }

    /** 启动工作线程（幂等）；ERROR 后重启也走这里。 */
    public synchronized void start() {
        stopped = false;
        offline = false;
        if (worker != null && worker.isAlive()) {
            return;
        }
        fails = 0;
        noFrameFails = 0;
        worker = new Thread(this::runLoop, "wall-rtsp-" + shorten(did) + "-" + seq.incrementAndGet());
        worker.setDaemon(true);
        worker.start();
    }

    /** board 标记离线：停抓取进 OFFLINE，不销毁实例，上线后 {@link #goOnline()} 恢复。 */
    public synchronized void goOffline() {
        if (offline) {
            return;
        }
        offline = true;
        closeGrabber();
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** board 标记上线：清除离线态并保证工作线程存活。 */
    public synchronized void goOnline() {
        if (!offline && worker != null && worker.isAlive()) {
            return;
        }
        offline = false;
        fails = 0;
        noFrameFails = 0;
        if (worker == null || !worker.isAlive()) {
            start();
        }
    }

    /** 永久停止并释放全部 native 资源。 */
    public synchronized void stop() {
        stopped = true;
        offline = false;
        closeGrabber();
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        setState(PlayerState.STOPPED);
    }

    /**
     * 手动刷新连接（快捷键触发）：立即中断当前取流并重置失败计数。
     * worker 存活时靠关闭 grabber 打断阻塞抓取、由主循环自然重连；
     * 已进 ERROR（worker 退出）则重新拉起工作线程。离线/已销毁实例不处理。
     */
    public synchronized void reconnect() {
        if (stopped || offline) {
            return;
        }
        fails = 0;
        noFrameFails = 0;
        lastWatchdogCloseMs = 0L;
        if (worker == null || !worker.isAlive()) {
            log.info("[{}] 手动刷新：工作线程已退出，重新启动", did);
            start();
            return;
        }
        log.info("[{}] 手动刷新：断开当前取流，立即重连", did);
        refreshRequested = true;
        closeGrabber();
    }

    /** 看门狗定时检查（外部每 2s 调用一次）。 */
    public void tickWatchdog(long nowMs) {
        // 一次重建后给 worker 循环留出退出/重连窗口，避免同一卡住的连接被每 2s 重复关闭刷屏
        if (nowMs - lastWatchdogCloseMs < CONNECT_LIMIT_MS) {
            return;
        }
        PlayerState s = state;
        if (s == PlayerState.PLAYING && nowMs - lastFrameMs > STALL_LIMIT_MS) {
            log.warn("[{}] 帧停滞超过 {}ms，重建取流", did, STALL_LIMIT_MS);
            lastWatchdogCloseMs = nowMs;
            closeGrabber();
        } else if (s == PlayerState.CONNECTING && connectStartMs > 0L
                && nowMs - connectStartMs > CONNECT_LIMIT_MS) {
            log.warn("[{}] 建连超过 {}ms 无首帧，重建取流", did, CONNECT_LIMIT_MS);
            lastWatchdogCloseMs = nowMs;
            closeGrabber();
        }
    }

    // ===================== 抓取线程主循环 =====================

    private void runLoop() {
        log.info("[{}] 播放线程启动（原始分辨率，不做 native 缩放），url={}", did, rtspUrl);
        while (!stopped) {
            if (offline) {
                setState(PlayerState.OFFLINE);
                sleepQuiet(500L);
                continue;
            }
            FFmpegFrameGrabber g = null;
            boolean reachedPlaying = false;
            try {
                setState(PlayerState.CONNECTING);
                // start() 会在 JavaCV 的 avcodec 全局锁上排队，排队期间不计入首帧超时：
                // 先清零，待 start 成功返回、真正开始收流后再打点
                connectStartMs = 0L;
                g = buildGrabber();
                g.start();
                grabber = g;
                connectStartMs = System.currentTimeMillis();
                log.info("[{}] RTSP 已连接，开始解码", did);

                while (!stopped && !offline) {
                    Frame frame = g.grab();
                    if (frame == null) {
                        log.info("[{}] 取流结束（服务端关闭），进入重连", did);
                        break;
                    }
                    if (frame.image == null) {
                        continue; // 音频帧/空帧，一期静音忽略
                    }
                    renderFrame(frame);
                    reachedPlaying = true;
                    if (state != PlayerState.PLAYING) {
                        setState(PlayerState.PLAYING);
                    }
                    fails = 0;
                    noFrameFails = 0;
                }
            } catch (Exception e) {
                if (!stopped && !offline) {
                    log.warn("[{}] 取流异常：{}", did, e.toString());
                }
            } finally {
                closeGrabber();
            }

            if (stopped) {
                break;
            }
            if (offline) {
                setState(PlayerState.OFFLINE);
                continue;
            }
            if (refreshRequested) {
                // 手动刷新：不累计失败、不走退避，立即进入下一轮建连
                refreshRequested = false;
                fails = 0;
                noFrameFails = 0;
                log.info("[{}] 手动刷新：跳过退避，立即重建连接", did);
                continue;
            }
            if (!reachedPlaying) {
                // RTSP 建连成功但整个会话从未拿到首帧：多为设备本地链路不通、服务端无视频可发。
                // 快速判死并退避，避免 grabber 反复空连忙等拖慢同屏其他路。
                noFrameFails++;
                if (noFrameFails >= NO_FRAME_FAIL_LIMIT) {
                    log.error("[{}] 连续 {} 次建连后无首帧，进入 ERROR 等待 reconcile/F5", did, noFrameFails);
                    setState(PlayerState.ERROR);
                    return;
                }
                log.warn("[{}] 建连后无首帧（第 {}/{} 次），{}ms 后重试",
                        did, noFrameFails, NO_FRAME_FAIL_LIMIT, NO_FRAME_BACKOFF_MS);
                setState(PlayerState.RECONNECTING);
                sleepQuiet(NO_FRAME_BACKOFF_MS);
                continue;
            }
            fails++;
            if (fails >= MAX_FAILS) {
                log.error("[{}] 连续 {} 次取流失败，进入 ERROR 等待 reconcile", did, fails);
                setState(PlayerState.ERROR);
                return;
            }
            setState(PlayerState.RECONNECTING);
            sleepQuiet(BACKOFF_BASE_MS * fails);
        }
        setState(PlayerState.STOPPED);
        log.info("[{}] 播放线程退出", did);
    }

    private FFmpegFrameGrabber buildGrabber() {
        FFmpegFrameGrabber g = new FFmpegFrameGrabber(rtspUrl);
        // 服务端仅支持 RTP-over-TCP interleaved
        g.setOption("rtsp_transport", "tcp");
        // 低延迟直播：解码器低延迟 + 缩短探测。
        // 注意：不能加 fflags=nobuffer——实测它会关闭封装层包缓冲，使本服务端 RTP
        // 承载的 H.265 帧重组缺失参考帧（Could not find ref with POC），sws 输出整片灰屏。
        g.setOption("flags", "low_delay");
        g.setOption("probesize", "32768");
        g.setOption("analyzeduration", "500000");
        g.setOption("max_delay", "500000");
        // 仅 SETUP 视频 track（FFmpeg 6.1 RTSP 选项）；若个别构建不认该选项，
        // 音频 track 即使被 SETUP 也只是在此被忽略，不影响出画
        g.setOption("allowed_media_types", "video");
        // RTSP/TCP 套接字 I/O 读超时（微秒）。关键：JavaCV 1.5.10 的 start() 内部以
        // synchronized(avcodec.class) 全局锁串行执行 avformat_find_stream_info，某路若连上却
        // 收不到视频参数（设备本地链路不通的死流），会在锁内无限等待并阻塞同进程其他所有路
        // start()。设置有限 I/O 超时后，死流在数秒内读超时抛错、释放全局锁，健康路得以启动。
        // 直播源帧间隔约 50ms，3s 阈值不会误伤正常播放，网络真断流时也能更快进入重连。
        g.setOption("timeout", "3000000");
        // 限制每路解码器只用单线程：FFmpeg 默认按 CPU 逻辑核数为每路开帧线程，多屏并发时
        // 帧线程总数超订阅、互相抢占会导致大面积首帧超时（实测三路默认线程 60s 零首帧）。
        // 实测本机 threads=1 下连 3200x1800 HEVC 单路也能稳定跑满源 ~20fps，多路总线程可控。
        g.setVideoOption("threads", "1");
        // swscale 只做颜色转换、保留摄像头原始分辨率，不做 native 缩放：FFmpeg 对无透明通道
        // 源填充 alpha=255（已实测），可整块拷贝上屏。缩放统一交给 ImageView（GPU 平滑缩放），
        // 因此画面清晰度与格子大小解耦，任意窗口尺寸/分割比例下都用源全分辨率位图。
        g.setPixelFormat(avutil.AV_PIX_FMT_BGRA);
        return g;
    }

    // ===================== 帧渲染 =====================

    private void renderFrame(Frame frame) {
        int w = frame.imageWidth;
        int h = frame.imageHeight;
        if (w <= 0 || h <= 0 || frame.image == null || frame.image.length == 0) {
            return;
        }
        if (pixelBuffer == null || w != surfaceW || h != surfaceH) {
            // WritableImage/PixelBuffer 必须在 FX 线程创建；同步等待完成后再写缓冲
            log.info("[{}] 实际帧尺寸 {}x{}，重建上屏缓冲", did, w, h);
            runOnFxAndWait(() -> {
                allocateSurface(w, h);
                listener.onImageChanged(this);
            });
        }
        // swscale 已直接输出 BGRA（实测 alpha 字节恒为 255），每像素 4 字节；
        // imageStride 可能因行对齐大于 w*4，按行整块拷贝到紧密缓冲，避免逐像素 Java 循环
        int stride = Math.max(frame.imageStride, w * 4);
        int rowBytesLen = w * 4;
        if (rowBuffer == null || rowBuffer.length < rowBytesLen) {
            rowBuffer = new byte[rowBytesLen];
        }
        ByteBuffer src = (ByteBuffer) frame.image[0];

        synchronized (bufferLock) {
            bgraBuffer.clear();
            for (int r = 0; r < h; r++) {
                src.position(r * stride);
                src.get(rowBuffer, 0, rowBytesLen);
                bgraBuffer.put(rowBuffer, 0, rowBytesLen);
            }
            bgraBuffer.flip();
        }

        long now = System.currentTimeMillis();
        lastFrameMs = now;
        if (now - lastPushMs >= PUSH_INTERVAL_MS) {
            // updateBuffer 与渲染 pulse 均在 FX 线程，持同一把锁避免与写缓冲并发
            final PixelBuffer<ByteBuffer> pb = pixelBuffer;
            Platform.runLater(() -> {
                synchronized (bufferLock) {
                    pb.updateBuffer(buf -> null);
                }
            });
            lastPushMs = now;
        }
    }

    /** 仅在 FX 线程调用（构造期本就在 FX 线程，运行期经 runOnFxAndWait 切换）。 */
    private void allocateSurface(int w, int h) {
        bgraBuffer = ByteBuffer.allocateDirect(w * h * 4);
        pixelBuffer = new PixelBuffer<>(w, h, bgraBuffer,
                PixelFormat.getByteBgraPreInstance());
        image = new WritableImage(pixelBuffer);
        surfaceW = w;
        surfaceH = h;
    }

    private void runOnFxAndWait(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeGrabber() {
        FFmpegFrameGrabber g = grabber;
        grabber = null;
        if (g == null) {
            return;
        }
        try {
            g.stop();
        } catch (FrameGrabber.Exception e) {
            log.debug("[{}] grabber.stop 异常：{}", did, e.toString());
        }
        try {
            g.release();
        } catch (FrameGrabber.Exception e) {
            log.debug("[{}] grabber.release 异常：{}", did, e.toString());
        }
    }

    private void setState(PlayerState next) {
        PlayerState old = state;
        state = next;
        if (old != next) {
            log.info("[{}] 播放状态 {} -> {}", did, old, next);
            listener.onStateChanged(this, next);
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shorten(String did) {
        if (did == null) {
            return "unknown";
        }
        return did.length() <= 8 ? did : did.substring(0, 8);
    }
}
