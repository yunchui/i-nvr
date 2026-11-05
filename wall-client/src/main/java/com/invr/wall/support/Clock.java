package com.invr.wall.support;

/**
 * 服务端时钟校准：用 board 响应的 serverTime 计算本地与服务端的偏移，
 * 事件 expireAt 兜底计时一律以服务端时钟为准，避免大屏机本地时钟漂移导致复原不准。
 */
public final class Clock {

    private volatile long offsetMs = 0L;

    /** 用服务端时间戳校准（每次 board 拉取都可调用，持续修正）。 */
    public void calibrate(long serverTimeMs) {
        this.offsetMs = serverTimeMs - System.currentTimeMillis();
    }

    /** 当前时刻对应的服务端时间（毫秒）。 */
    public long serverNow() {
        return System.currentTimeMillis() + offsetMs;
    }

    /** 服务端时间戳距今的毫秒数（最小 0）。 */
    public long millisUntil(long serverTimestampMs) {
        long d = serverTimestampMs - serverNow();
        return Math.max(0L, d);
    }
}
