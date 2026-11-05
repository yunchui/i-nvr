package com.invr.wall.media;

/**
 * 单路 RTSP 播放器状态。
 *
 * <pre>
 * IDLE → CONNECTING → PLAYING ⇄ RECONNECTING → ERROR（等待 reconcile 重启）
 *                  ↘ OFFLINE（board 标记离线，上线后回到 CONNECTING）
 * 任意状态 → STOPPED（永久关闭，释放资源）
 * </pre>
 */
public enum PlayerState {
    IDLE,
    CONNECTING,
    PLAYING,
    RECONNECTING,
    OFFLINE,
    ERROR,
    STOPPED
}
