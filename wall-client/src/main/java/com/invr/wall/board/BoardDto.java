package com.invr.wall.board;

import java.util.List;

/**
 * {@code GET /share/{token}/board} 响应的客户端模型，字段与服务端 SharePageController 下发一致。
 * 未知字段（hlsUrl 等 Web 专用项）容忍忽略。
 */
public class BoardDto {

    /** 看板名称。 */
    public String name;
    /** 布局：grid4 / grid9 / grid6。 */
    public String layout;
    /** 有序槽位（坐标 + did + 在线状态）。 */
    public List<Slot> slots;
    /** 服务端事件 SSE 相对路径（客户端基于 baseUrl 自拼，此字段仅留档）。 */
    public String eventsUrl;
    /** 响应时刻的服务端时间戳（毫秒），用于时钟校准。 */
    public long serverTime;
    /** 看板过期时间戳；null 表示永久有效。 */
    public Long expireAt;
    /** 服务端生效的配置轮询周期（秒）。 */
    public long configPollSec = 60L;
    /** 服务端生效的页面兜底重载周期（秒），原生客户端不做整页重载，仅参考。 */
    public long pageReloadSec = 3600L;
    /** RTSP 下行坐标；旧版服务端可能不下发，此时为 null。 */
    public RtspInfo rtsp;

    public static class RtspInfo {
        public String host;
        public int port;
    }

    public static class Slot {
        /** 槽位标识：grid6 为 BIG/S1..S5，其余为 "1".."9"。 */
        public String slot;
        public int colStart;
        public int rowStart;
        public int colSpan;
        public int rowSpan;
        /** 摄像头 did；空置槽位为 null。 */
        public String did;
        public String cameraName;
        public boolean online;
        /** Web 端 HLS 地址，原生客户端不使用。 */
        public String hlsUrl;
    }
}
