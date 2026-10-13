package com.invr.p2p.cs2;

/**
 * CS2 协议常量（1:1 对照 go2rtc pkg/xiaomi/miss/cs2/conn.go）。
 */
public final class Cs2 {

    private Cs2() {
    }

    /** 外层消息首字节 magic。 */
    public static final int MAGIC = 0xF1;
    /** DRW 数据段 magic。 */
    public static final int MAGIC_DRW = 0xD1;
    /** TCP 外层帧标记字节。 */
    public static final int MAGIC_TCP = 0x68;

    public static final int MSG_LAN_SEARCH = 0x30;
    public static final int MSG_PUNCH_PKT = 0x41;
    public static final int MSG_P2P_RDY_UDP = 0x42;
    public static final int MSG_P2P_RDY_TCP = 0x43;
    public static final int MSG_DRW = 0xD0;
    public static final int MSG_DRW_ACK = 0xD1;
    public static final int MSG_PING = 0xE0;
    public static final int MSG_PONG = 0xE1;
    public static final int MSG_CLOSE = 0xF0;
    public static final int MSG_CLOSE_ACK = 0xF1;

    /** 通道：命令。 */
    public static final int CHANNEL_CMD = 0;
    /** 通道：媒体下行。 */
    public static final int CHANNEL_MEDIA = 2;
    /** 通道：音频上行。 */
    public static final int CHANNEL_AUDIO = 3;

    /** 设备固定监听端口。 */
    public static final int DEVICE_PORT = 32108;
    /** 握手总超时。 */
    public static final int HANDSHAKE_TIMEOUT_MS = 5_000;
    /** 握手重发间隔 / UDP 命令重传间隔。 */
    public static final int RETRY_INTERVAL_MS = 1_000;
    /** UDP 命令最大发送次数。 */
    public static final int CMD_MAX_SENDS = 5;
    /** TCP 拨号超时。 */
    public static final int TCP_DIAL_TIMEOUT_MS = 3_000;
    /** TCP PING 间隔。 */
    public static final int TCP_PING_INTERVAL_MS = 1_000;
    /** MISS 登录等命令响应超时。 */
    public static final int LOGIN_RESPONSE_TIMEOUT_MS = 10_000;
    /** 取流阶段等待媒体包超时（超过判定断流）。 */
    public static final int MEDIA_READ_TIMEOUT_MS = 15_000;

    /** 收包缓冲（go2rtc 固定 1200）。 */
    public static final int READ_BUF_SIZE = 1200;
    /** 媒体消息头长度。 */
    public static final int HDR_SIZE = 32;
}
