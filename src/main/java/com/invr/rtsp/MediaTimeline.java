package com.invr.rtsp;

/**
 * 媒体时间轴归一化：把设备 32 位毫秒时钟（低 32 位、约 49 天回绕）映射为从 0 开始、
 * 单调连续的输出毫秒时间轴。视频与音频共享同一实例（真机证实二者同一时钟），保证 A/V 同步。
 *
 * <p>处理：①32 位回绕；②重连后时间戳异常跳变（不拉大缺口，避免播放器长时间卡顿）。</p>
 */
public final class MediaTimeline {

    private static final long WRAP = 1L << 32;
    /** 相邻包时间差超过该值（30s）视为异常跳变/重连时间戳重置，不计入输出时间轴。 */
    private static final long MAX_SANE_DELTA_MS = 30_000L;

    private boolean initialized;
    private long lastDev;
    private long outMs;

    /**
     * @param devTimestampMs 媒体包头部的设备毫秒时间戳（原始低 32 位也可，内部会掩码）
     * @return 归一化后的输出毫秒（从 0 开始、单调不减）
     */
    public synchronized long normalize(long devTimestampMs) {
        long dev = devTimestampMs & 0xFFFFFFFFL;
        if (!initialized) {
            initialized = true;
            lastDev = dev;
            outMs = 0;
            return 0;
        }
        long delta = dev - lastDev;
        if (delta < 0) {
            delta += WRAP; // 回绕
        }
        if (delta < 0 || delta > MAX_SANE_DELTA_MS) {
            // 异常跳变（重连后时间戳重置等）：时间轴不产生大缺口
            delta = 0;
        }
        outMs += delta;
        lastDev = dev;
        return outMs;
    }

    public synchronized void reset() {
        initialized = false;
        outMs = 0;
        lastDev = 0;
    }
}
