package com.invr.hub;

import com.invr.miss.MissClient;
import com.invr.miss.MissPacket;
import com.invr.miss.NalUnits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 单台摄像头的唯一上游取流会话（单通道复用的核心）。
 *
 * <p>对同一台设备，全局只允许存在一个 {@code CameraStream}，也就只占用一条 CS2/MISS 上游连接；
 * 录像、RTSP、CLI 落盘等都作为 {@link StreamSink} 订阅到这里，由唯一的上游取流线程扇出。</p>
 *
 * <p>职责：</p>
 * <ul>
 *   <li><b>引用计数</b>：第一个订阅者到来时拉起上游，最后一个离开时停止（VideoStop/断连），不空占通道。</li>
 *   <li><b>自动重连</b>：读包异常 / 15s 无媒体包即判定断流，重新 miss_get_vendor→握手→登录→VideoStart，
 *       退避 1/2/4/8/16/30s 封顶，期间无限重试。</li>
 *   <li><b>GOP 缓存</b>：缓存最近一个关键帧（含 VPS/SPS/PPS/IDR）以来的视频包，新订阅者立即回放，做到秒开。</li>
 *   <li><b>隔离慢消费者</b>：每个订阅者独立有界队列 + 独立派发线程，任何消费者阻塞都不会拖垮上游取流。</li>
 * </ul>
 */
public final class CameraStream {

    private static final Logger log = LoggerFactory.getLogger(CameraStream.class);

    /** 单个订阅者的有界队列容量（包）。录像写盘很快，正常不会触顶；满则丢该包并计数告警。 */
    private static final int SUBSCRIBER_QUEUE_CAPACITY = 1000;
    /** GOP 缓存硬上限，防止极端情况下内存膨胀（3.5K 一个 GOP 通常远小于此）。 */
    private static final int GOP_MAX_BYTES = 16 * 1024 * 1024;
    /**
     * 重连退避：首跳 200ms（对齐 go2rtc——CS2 上游约每 110s 固有断一次，设备接受立即重连），
     * 仅在连续失败时才翻倍退避并封顶 30s。
     */
    private static final long[] BACKOFF_MS = {200, 500, 1_000, 2_000, 4_000, 8_000, 16_000, 30_000};

    private final String did;
    private final String model;
    private final MissOpener opener;

    private final Object lifecycleLock = new Object();
    /** 上游分发锁：串行化"取流广播 / 重连 reset / 新订阅回放 GOP"，保证不重不漏。 */
    private final Object deliverLock = new Object();

    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final List<MissPacket> gop = new ArrayList<>();
    private long gopBytes;

    private volatile boolean running;
    private Thread upstreamThread;
    private volatile MissClient current;
    private volatile int generation;

    public CameraStream(String did, String model, MissOpener opener) {
        this.did = did;
        this.model = model;
        this.opener = opener;
    }

    public String did() {
        return did;
    }

    public String model() {
        return model;
    }

    public int generation() {
        return generation;
    }

    public int subscriberCount() {
        return subscriptions.size();
    }

    /**
     * 订阅该摄像头。首个订阅会启动唯一上游；新订阅立即获得当前 GOP 回放。
     */
    public void subscribe(StreamSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink 不能为空");
        }
        boolean needStart;
        Subscription sub = new Subscription(sink);
        synchronized (lifecycleLock) {
            needStart = !running;
            if (needStart) {
                running = true;
                startUpstream();
            }
            subscriptions.add(sub);
            synchronized (deliverLock) {
                sub.start();
                // 先告知当前代数（未连接时为 0，连接成功后会再 reset 一次），再回放当前 GOP
                sub.dispatchReset(generation);
                for (MissPacket p : gop) {
                    sub.offerPacket(new PacketEvent(generation, p));
                }
            }
        }
        log.info("摄像头 {} 新增订阅者（当前 {} 个，{}）", did, subscriptions.size(),
                needStart ? "已请求启动唯一上游" : "复用现有上游");
    }

    /**
     * 取消订阅；最后一个取消时停止并断开唯一上游，释放通道。
     */
    public void unsubscribe(StreamSink sink) {
        boolean needStop = false;
        Thread toJoin = null;
        synchronized (lifecycleLock) {
            Subscription found = null;
            for (Subscription s : subscriptions) {
                if (s.sink == sink) {
                    found = s;
                    break;
                }
            }
            if (found == null) {
                return;
            }
            subscriptions.remove(found);
            found.close();
            log.info("摄像头 {} 移除订阅者（剩余 {} 个）", did, subscriptions.size());
            if (subscriptions.isEmpty() && running) {
                running = false;
                needStop = true;
                toJoin = upstreamThread;
                if (upstreamThread != null) {
                    upstreamThread.interrupt();
                }
                MissClient c = current;
                current = null;
                closeQuietly(c);
            }
        }
        if (needStop && toJoin != null) {
            try {
                toJoin.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("摄像头 {} 已无订阅者，唯一上游通道已释放", did);
        }
    }

    /** 彻底停止（服务关闭时调用）。 */
    public void shutdown() {
        synchronized (lifecycleLock) {
            for (Subscription s : subscriptions) {
                s.close();
            }
            subscriptions.clear();
            if (running) {
                running = false;
                if (upstreamThread != null) {
                    upstreamThread.interrupt();
                }
                MissClient c = current;
                current = null;
                closeQuietly(c);
            }
        }
    }

    // ------------------------------------------------------------------
    // 唯一上游取流线程
    // ------------------------------------------------------------------

    private void startUpstream() {
        generation = 0;
        upstreamThread = new Thread(this::runUpstream, "cs2-upstream-" + did);
        upstreamThread.setDaemon(true);
        upstreamThread.start();
    }

    private void runUpstream() {
        int backoffIndex = 0;
        while (running) {
            MissClient client = null;
            int gen = 0;
            try {
                log.info("[{}] 正在建立上游取流连接（重新协商+CS2+MISS+VideoStart）...", did);
                client = opener.open();
                current = client;
                synchronized (deliverLock) {
                    gen = ++generation;
                    gop.clear();
                    gopBytes = 0;
                    for (Subscription s : subscriptions) {
                        s.dispatchReset(gen);
                    }
                }
                log.info("[{}] 上游已连接并开始推流（generation={}，{}）", did, gen, client.protocol());
                backoffIndex = 0;

                while (running) {
                    MissPacket pkt = client.readPacket();
                    if (pkt == null) {
                        continue;
                    }
                    synchronized (deliverLock) {
                        if (pkt.isVideo()) {
                            cacheGop(pkt);
                        }
                        PacketEvent ev = new PacketEvent(gen, pkt);
                        for (Subscription s : subscriptions) {
                            s.offerPacket(ev);
                        }
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                long wait = BACKOFF_MS[backoffIndex];
                log.warn("[{}] 上游取流中断（generation={}）：{}；{}s 后自动重连（将重新协商）",
                        did, gen, e.toString(), wait / 1000);
                closeQuietly(client);
                client = null;
                current = null;
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (!running) {
                        break;
                    }
                }
                if (backoffIndex < BACKOFF_MS.length - 1) {
                    backoffIndex++;
                }
            } finally {
                if (client != null) {
                    closeQuietly(client);
                    if (current == client) {
                        current = null;
                    }
                }
            }
        }
        log.info("[{}] 上游取流线程退出", did);
    }

    private void cacheGop(MissPacket p) {
        if (NalUnits.containsIdr(p.isH265(), p.payload())) {
            gop.clear();
            gopBytes = 0;
        }
        if (gopBytes < GOP_MAX_BYTES) {
            gop.add(p);
            gopBytes += p.payload().length;
        }
    }

    private static void closeQuietly(MissClient c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            log.debug("关闭上游连接异常（忽略）：{}", e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 订阅者：独立有界队列 + 独立派发线程，隔离慢消费者
    // ------------------------------------------------------------------

    /** 上游事件（代数重置 or 媒体包）。 */
    private sealed interface HubEvent permits ResetEvent, PacketEvent {
        int generation();
    }

    private record ResetEvent(int generation) implements HubEvent {
    }

    private record PacketEvent(int generation, MissPacket packet) implements HubEvent {
    }

    private static final Object POISON = new Object();

    private final class Subscription {
        final StreamSink sink;
        @SuppressWarnings("unchecked")
        final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE_CAPACITY);
        Thread dispatcher;
        volatile boolean open = true;
        long dropped;

        Subscription(StreamSink sink) {
            this.sink = sink;
        }

        void start() {
            dispatcher = new Thread(this::runDispatch, "sink-dispatch-" + did + "-" + Integer.toHexString(hashCode()));
            dispatcher.setDaemon(true);
            dispatcher.start();
        }

        /** 新代数：旧积压包已无意义，清空后必达一个 reset。 */
        void dispatchReset(int gen) {
            queue.clear();
            queue.offer(new ResetEvent(gen));
        }

        void offerPacket(PacketEvent ev) {
            if (!queue.offer(ev)) {
                dropped++;
                if (dropped == 1 || dropped % 200 == 0) {
                    log.warn("[{}] 订阅者 {} 队列已满，丢弃 {} 包（消费者过慢）",
                            did, sink.getClass().getSimpleName(), dropped);
                }
            }
        }

        void close() {
            open = false;
            queue.offer(POISON);
        }

        private void runDispatch() {
            try {
                while (open || !queue.isEmpty()) {
                    Object e = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (e == null) {
                        continue;
                    }
                    if (e == POISON) {
                        break;
                    }
                    if (e instanceof ResetEvent r) {
                        sink.onReset(r.generation());
                    } else if (e instanceof PacketEvent p) {
                        sink.onPacket(p.packet(), p.generation());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                log.error("[{}] 订阅者 {} 回调异常：{}", did, sink.getClass().getSimpleName(), t.toString(), t);
            }
        }
    }
}
