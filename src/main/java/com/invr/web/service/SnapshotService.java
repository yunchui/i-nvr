package com.invr.web.service;

import com.invr.codec.SnapshotDecoder;
import com.invr.hub.CameraConnector;
import com.invr.hub.StreamHub;
import com.invr.hub.StreamSink;
import com.invr.miss.MissPacket;
import com.invr.model.CameraDevice;
import com.invr.web.api.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 摄像头实时截帧服务：对外提供“按 DID 取当前一帧 JPEG”。
 *
 * <p>每次请求创建一个一次性 {@link SnapshotDecoder} 作为 {@link StreamSink} 订阅全局
 * {@link StreamHub}——与录像 / RTSP / HLS 复用同一条摄像头上游，不额外建连；新订阅会立即
 * 收到 Hub 回放的当前 GOP（以 IDR 起始），热态下通常数十毫秒即可出帧。解码线程为该订阅
 * 专属的派发线程，请求线程仅等待结果。首帧到手（或超时）后立即退订并释放全部 native 资源。</p>
 *
 * <p>冷启动（该摄像头当前无任何消费者）需先完成 CS2 建连与首个 IDR，故超时上限放宽到
 * {@value #CAPTURE_TIMEOUT_MS} ms；另有信号量限制并发截帧路数，避免异常调用堆积 native 解码器。</p>
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    /** 冷启动建连 + 等关键帧的整体上限，超时返回 503 让调用方重试。 */
    private static final long CAPTURE_TIMEOUT_MS = 20_000;
    /** 同时进行的截帧解码上限（每个解码器都是独立 native 上下文）。 */
    private static final int MAX_INFLIGHT = 8;

    private final StreamHub hub;
    private final CameraConnector connector;
    private final Semaphore inflight = new Semaphore(MAX_INFLIGHT, true);

    public SnapshotService(StreamHub hub, CameraConnector connector) {
        this.hub = hub;
        this.connector = connector;
    }

    /**
     * 截取指定摄像头当前画面一帧。
     *
     * @param did 摄像头 DID
     * @return 完整 JPEG 字节（image/jpeg）
     * @throws ApiException 设备不存在/离线、并发过多、超时或解码失败
     */
    public byte[] capture(String did) {
        CameraDevice device = connector.findDevice(did);
        if (device == null) {
            throw ApiException.notFound("设备列表中未找到 did=" + did);
        }
        if (!device.online()) {
            throw new ApiException(503, "摄像头当前不在线，无法截取画面");
        }
        if (!inflight.tryAcquire()) {
            throw new ApiException(503, "当前截帧请求过多，请稍后再试");
        }

        SnapshotDecoder decoder = new SnapshotDecoder();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        StreamSink sink = new StreamSink() {
            @Override
            public void onReset(int generation) {
                try {
                    decoder.reset();
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            }

            @Override
            public void onPacket(MissPacket packet, int generation) {
                if (result.isDone()) {
                    return;
                }
                try {
                    byte[] jpeg = decoder.accept(packet);
                    if (jpeg != null) {
                        result.complete(jpeg);
                    }
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            }
        };

        // 订阅即触发上游拉起（或复用现有上游），并同步回放当前 GOP
        hub.subscribe(did, device.model(), connector.openerFor(device), sink);
        try {
            return result.get(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ApiException(503, "截取摄像头画面超时，请确认设备在线后重试");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException api) {
                throw api;
            }
            String msg = cause == null ? e.toString() : cause.getMessage();
            throw new ApiException(500, "截帧失败：" + (msg == null ? cause.toString() : msg));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "截帧请求被中断");
        } finally {
            hub.unsubscribe(did, sink);
            decoder.close();
            inflight.release();
        }
    }

    @PreDestroy
    public void shutdown() {
        // 解码器随请求结束自行释放；这里仅留统一关闭钩子，避免容器销毁时残留等待
        log.debug("SnapshotService 关闭，在途请求 {} 个", MAX_INFLIGHT - inflight.availablePermits());
    }
}
