package com.invr.miss;

/**
 * MISS 媒体包（CS2 通道 2，32 字节小端头解密后）。
 *
 * @param codecId      编码标识：4=H264、5=H265、1024=PCM、1026=PCMU、1027=PCMA、1032=Opus
 * @param sequence     包序号
 * @param flags        标志位（音频采样率在其中编码）
 * @param timestampMs  时间戳（毫秒）
 * @param payload      已解密负载（视频为 Annex-B NALU 流）
 */
public record MissPacket(
        int codecId,
        int sequence,
        int flags,
        long timestampMs,
        byte[] payload
) {

    public boolean isVideo() {
        return codecId == MissCommands.CODEC_H264 || codecId == MissCommands.CODEC_H265;
    }

    public boolean isH265() {
        return codecId == MissCommands.CODEC_H265;
    }

    /**
     * 音频采样率：{@code (flags>>3)&0xF != 0 → 16000Hz，否则 8000Hz}。
     */
    public int sampleRate() {
        return ((flags >>> 3) & 0xF) != 0 ? 16_000 : 8_000;
    }
}
