package com.invr.rtsp;

import java.util.Random;

/**
 * RTP 固定头封装与序号管理（RFC 3550）。视频、音频各持一个实例（独立 SSRC/序号）。
 */
public final class RtpEncoder {

    private final int ssrc;
    private int seq;

    public RtpEncoder() {
        this(new Random().nextInt(), new Random().nextInt(65536));
    }

    public RtpEncoder(int ssrc, int initialSeq) {
        this.ssrc = ssrc;
        this.seq = initialSeq & 0xFFFF;
    }

    public int ssrc() {
        return ssrc;
    }

    /**
     * 构造完整 RTP 报文（12B 固定头 + payload）。
     *
     * @param payloadType 动态负载类型（视频 96 / 音频 97）
     * @param marker      标记位（视频=访问单元最后一个包；音频每个包=1）
     * @param timestamp32 RTP 时间戳（已按时钟换算，取低 32 位）
     */
    public byte[] build(int payloadType, boolean marker, long timestamp32, byte[] payload, int off, int len) {
        byte[] pkt = new byte[RtpPacker.RTP_HEADER_LEN + len];
        pkt[0] = (byte) 0x80; // V=2, P=0, X=0, CC=0
        pkt[1] = (byte) ((marker ? 0x80 : 0) | (payloadType & 0x7F));
        pkt[2] = (byte) ((seq >>> 8) & 0xFF);
        pkt[3] = (byte) (seq & 0xFF);
        seq = (seq + 1) & 0xFFFF;
        int ts = (int) (timestamp32 & 0xFFFFFFFFL);
        pkt[4] = (byte) ((ts >>> 24) & 0xFF);
        pkt[5] = (byte) ((ts >>> 16) & 0xFF);
        pkt[6] = (byte) ((ts >>> 8) & 0xFF);
        pkt[7] = (byte) (ts & 0xFF);
        pkt[8] = (byte) ((ssrc >>> 24) & 0xFF);
        pkt[9] = (byte) ((ssrc >>> 16) & 0xFF);
        pkt[10] = (byte) ((ssrc >>> 8) & 0xFF);
        pkt[11] = (byte) (ssrc & 0xFF);
        System.arraycopy(payload, off, pkt, RtpPacker.RTP_HEADER_LEN, len);
        return pkt;
    }

    public byte[] build(int payloadType, boolean marker, long timestamp32, byte[] payload) {
        return build(payloadType, marker, timestamp32, payload, 0, payload.length);
    }
}
