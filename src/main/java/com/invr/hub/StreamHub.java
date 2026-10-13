package com.invr.hub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 取流总线：按设备 DID 管理唯一的 {@link CameraStream}，是"单通道复用"的全局强制点。
 *
 * <p>无论同时有多少录像任务、多少 RTSP 播放器、多少 CLI 在看同一台摄像头，
 * {@code StreamHub} 对该 DID 只维护一个 {@link CameraStream}，即只占用一条上游 CS2/MISS 连接；
 * 所有消费者作为 {@link StreamSink} 被同一条上游扇出。</p>
 *
 * <p>同一 DID 的订阅/退订通过 {@link ConcurrentHashMap#compute} 在该 key 上原子串行，
 * 避免"最后一个退订者移除实例"与"新订阅者复用实例"竞争而误建第二条上游。</p>
 */
public final class StreamHub {

    private static final Logger log = LoggerFactory.getLogger(StreamHub.class);

    private final ConcurrentHashMap<String, CameraStream> streams = new ConcurrentHashMap<>();

    /**
     * 订阅一台摄像头。
     *
     * @param did    设备 DID
     * @param model  型号（仅首次创建该设备流时使用）
     * @param opener 上游开启器（仅首次创建该设备流时使用；其内部每次重连都会重新协商）
     * @param sink   媒体消费者
     * @return 该设备对应的唯一 CameraStream
     */
    public CameraStream subscribe(String did, String model, MissOpener opener, StreamSink sink) {
        if (opener == null) {
            throw new IllegalArgumentException("opener 不能为空");
        }
        CameraStream cs = streams.compute(did, (d, existing) -> {
            CameraStream stream = existing != null ? existing : new CameraStream(d, model, opener);
            stream.subscribe(sink);
            return stream;
        });
        log.info("StreamHub 订阅 did={}（活跃上游设备数={}）", did, activeUpstreamCount());
        return cs;
    }

    /**
     * 退订一台摄像头；当该设备无任何消费者时，停止并移除其唯一上游。
     */
    public void unsubscribe(String did, StreamSink sink) {
        streams.compute(did, (d, stream) -> {
            if (stream == null) {
                return null;
            }
            stream.unsubscribe(sink);
            // 返回 null 即从 map 移除；compute 对同 key 原子，不会误删刚被复用的实例
            return stream.subscriberCount() == 0 ? null : stream;
        });
        log.info("StreamHub 退订 did={}（活跃上游设备数={}）", did, activeUpstreamCount());
    }

    /** 当前有订阅者、即真正占用上游通道的设备数。 */
    public int activeUpstreamCount() {
        int n = 0;
        for (CameraStream c : streams.values()) {
            if (c.subscriberCount() > 0) {
                n++;
            }
        }
        return n;
    }

    /** 停止全部上游（服务关闭）。 */
    public void shutdown() {
        for (CameraStream c : streams.values()) {
            c.shutdown();
        }
        streams.clear();
    }
}
