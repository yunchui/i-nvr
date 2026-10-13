package com.invr.hub;

import com.invr.miss.MissPacket;

/**
 * 媒体消费者：录像器、RTSP 会话、CLI 落盘等都实现该接口，订阅 {@link CameraStream}。
 *
 * <p>所有回调由该订阅专属的单线程按序触发，不同订阅之间互不阻塞、也不阻塞上游取流线程。</p>
 */
public interface StreamSink {

    /**
     * 上游（重新）建立并开始推流时回调一次。
     *
     * <p>每次自动重连都会产生新的 generation 并再次回调；消费者应在此清空上一段连接的
     * 时间基/缓存/封包序号。重连后设备会从 IDR（含 VPS/SPS/PPS）重新开始推，
     * 因此紧接着的视频包即可独立解码。</p>
     *
     * @param generation 连接代数，从 1 开始，每重连一次 +1
     */
    void onReset(int generation);

    /**
     * 收到一个媒体包（视频 Annex-B 或音频）。
     *
     * @param packet     已解密媒体包
     * @param generation 该包所属连接代数；与最近一次 {@link #onReset} 不一致的旧包应丢弃
     */
    void onPacket(MissPacket packet, int generation);
}
