package com.invr.rtsp;

import com.invr.hub.StreamSink;
import com.invr.miss.MissCommands;
import com.invr.miss.MissPacket;
import com.invr.miss.NalUnits;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 一个 RTSP 播放器会话对应的媒体消费者：订阅 {@link com.invr.hub.CameraStream} 的唯一上游，
 * 把小米私有的 Annex-B / Opus 包打成 RTP，经 RTP-over-TCP interleaved 信道下发给播放器。
 *
 * <p>多个播放器（或播放器 + 录像）对应多个 sink，但同一 DID 由 StreamHub 保证只占一条上游。</p>
 */
public final class RtspStreamSink implements StreamSink {

    private static final Logger log = LoggerFactory.getLogger(RtspStreamSink.class);

    public static final int PT_VIDEO = 96;
    public static final int PT_OPUS = 97;

    /** 侧载 GOP 缓存上限（PLAY 前缓存最近 IDR 起的视频，PLAY 时立即下发实现秒开）。 */
    private static final int GOP_CACHE_MAX_BYTES = 4 * 1024 * 1024;

    private final String did;
    private final Channel channel;
    private final int videoChannel; // interleaved 偶数信道（RTP）
    private final int audioChannel;

    private final RtpEncoder videoRtp = new RtpEncoder();
    private final RtpEncoder audioRtp = new RtpEncoder();
    private final MediaTimeline timeline = new MediaTimeline();

    private int currentGen;
    private volatile boolean h265Known;
    private volatile boolean isH265;

    // 参数集（供 SDP sprop）
    private volatile byte[] vps;
    private volatile byte[] sps;
    private volatile byte[] pps;
    private final CountDownLatch configLatch = new CountDownLatch(1);

    // PLAY 状态
    private boolean started;
    private boolean gopFlushPending;
    private final List<MissPacket> gopCache = new ArrayList<>();
    private long gopCacheBytes;

    private long videoPackets;
    private long audioPackets;
    private long rtpPackets;
    private long badPackets;

    public RtspStreamSink(String did, Channel channel, int videoChannel, int audioChannel) {
        this.did = did;
        this.channel = channel;
        this.videoChannel = videoChannel;
        this.audioChannel = audioChannel;
    }

    /** 等待参数集（VPS/SPS/PPS）到齐，供 DESCRIBE 生成 SDP。 */
    public boolean awaitConfig(long timeoutMs) throws InterruptedException {
        return configLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean isH265() {
        return isH265;
    }

    public boolean configReady() {
        if (!h265Known) {
            return false;
        }
        return isH265 ? (vps != null && sps != null && pps != null) : (sps != null && pps != null);
    }

    public byte[] vps() {
        return vps;
    }

    public byte[] sps() {
        return sps;
    }

    public byte[] pps() {
        return pps;
    }

    /** PLAY：开始下发；先 flush 缓存 GOP 实现秒开。仅在 dispatch 线程外调用，内部同步。 */
    public void startPlay() {
        synchronized (this) {
            started = true;
            gopFlushPending = !gopCache.isEmpty();
            if (gopFlushPending) {
                timeline.reset();
            }
        }
        log.info("[{}] RTSP PLAY 开始下发（{}，缓存GOP={}包）", did,
                h265Known ? (isH265 ? "H.265" : "H.264") : "视频待探测",
                gopCache.size());
    }

    @Override
    public void onReset(int generation) {
        currentGen = generation;
        synchronized (this) {
            // 重连后设备重新下发 IDR+参数集；清掉旧 GOP 缓存，等待新 IDR
            gopCache.clear();
            gopCacheBytes = 0;
            if (started) {
                gopFlushPending = true; // 重连后也等新 IDR 再续发，避免花屏
            }
        }
    }

    @Override
    public void onPacket(MissPacket pkt, int generation) {
        if (generation != currentGen) {
            return;
        }
        try {
            if (pkt.isVideo()) {
                handleVideo(pkt);
            } else if (pkt.codecId() == MissCommands.CODEC_OPUS) {
                handleAudio(pkt);
            }
            // 其它音频编码（PCM/PCMA/PCMU）M4 暂不下发
        } catch (RuntimeException e) {
            // 单个坏包/畸形 NAL 不应杀死整条订阅派发线程，跳过并计数
            badPackets++;
            if (badPackets == 1 || badPackets % 100 == 0) {
                log.warn("[{}] RTP 打包跳过坏包（累计 {}）：{}", did, badPackets, e.toString());
            }
        }
    }

    private void handleVideo(MissPacket pkt) {
        if (!h265Known) {
            isH265 = pkt.isH265();
            h265Known = true;
        }
        extractConfig(pkt);

        List<MissPacket> toFlush = null;
        boolean sendNow;
        synchronized (this) {
            // 维护侧载 GOP 缓存
            if (NalUnits.containsIdr(isH265, pkt.payload())) {
                gopCache.clear();
                gopCacheBytes = 0;
            }
            if (gopCacheBytes < GOP_CACHE_MAX_BYTES) {
                gopCache.add(pkt);
                gopCacheBytes += pkt.payload().length;
            }

            if (!started) {
                return;
            }
            if (gopFlushPending) {
                // 仅当本包是新 IDR 起的 GOP，才整体下发缓存
                if (NalUnits.containsIdr(isH265, pkt.payload())) {
                    toFlush = new ArrayList<>(gopCache);
                    gopFlushPending = false;
                } else {
                    return; // 等 IDR
                }
            }
            sendNow = true;
        }

        if (toFlush != null) {
            for (MissPacket cached : toFlush) {
                sendVideo(cached);
            }
        }
        if (sendNow && (toFlush == null || !toFlush.contains(pkt))) {
            sendVideo(pkt);
        }
    }

    private void sendVideo(MissPacket pkt) {
        List<NalUnits.Nal> nals = NalUnits.split(isH265, pkt.payload());
        if (nals.isEmpty()) {
            return;
        }
        List<RtpPacker.Fragment> fragments = isH265
                ? RtpPacker.packH265(nals)
                : RtpPacker.packH264(nals);
        long rtpTs = (timeline.normalize(pkt.timestampMs()) * 90L) & 0xFFFFFFFFL;
        int last = fragments.size() - 1;
        for (int i = 0; i < fragments.size(); i++) {
            byte[] rtp = videoRtp.build(PT_VIDEO, i == last, rtpTs, fragments.get(i).payload);
            writeInterleaved(videoChannel, rtp);
            rtpPackets++;
        }
        videoPackets++;
    }

    private void handleAudio(MissPacket pkt) {
        boolean send;
        synchronized (this) {
            send = started && !gopFlushPending;
        }
        if (!send) {
            return;
        }
        long rtpTs = (timeline.normalize(pkt.timestampMs()) * 48L) & 0xFFFFFFFFL; // 48kHz
        byte[] rtp = audioRtp.build(PT_OPUS, true, rtpTs, pkt.payload());
        writeInterleaved(audioChannel, rtp);
        rtpPackets++;
        audioPackets++;
    }

    private void extractConfig(MissPacket pkt) {
        if (configReady()) {
            return;
        }
        for (NalUnits.Nal nal : NalUnits.split(isH265, pkt.payload())) {
            if (isH265) {
                if (nal.type == 32) {
                    vps = nal.data;
                } else if (nal.type == 33) {
                    sps = nal.data;
                } else if (nal.type == 34) {
                    pps = nal.data;
                }
            } else {
                if (nal.type == 7) {
                    sps = nal.data;
                } else if (nal.type == 8) {
                    pps = nal.data;
                }
            }
        }
        if (configReady()) {
            configLatch.countDown();
            log.info("[{}] 已取得 {} 参数集（VPS/SPS/PPS），可生成 SDP", did,
                    isH265 ? "H.265" : "H.264");
        }
    }

    /** RTP-over-TCP interleaved 帧：0x24 + channel + BE16 length + RTP。 */
    private void writeInterleaved(int ch, byte[] rtp) {
        if (!channel.isActive()) {
            return;
        }
        ByteBuf buf = channel.alloc().buffer(4 + rtp.length);
        buf.writeByte(0x24);
        buf.writeByte(ch & 0xFF);
        buf.writeShort(rtp.length);
        buf.writeBytes(rtp);
        channel.writeAndFlush(buf);
    }

    public long videoPackets() {
        return videoPackets;
    }

    public long audioPackets() {
        return audioPackets;
    }

    public long rtpPackets() {
        return rtpPackets;
    }
}
