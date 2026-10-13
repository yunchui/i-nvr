package com.invr.hub;

import com.invr.miss.MissClient;

import java.io.IOException;

/**
 * 上游连接开启器：每次（重）连接时由 {@link CameraStream} 调用。
 *
 * <p>实现需完成完整的"建链到开始推流"全过程：重新 miss_get_vendor 协商（一次性签名/密钥，
 * 每次连接都要刷新）→ CS2 拨号 → MISS 登录 → VideoStart，返回一个已经在推流、
 * 可不断 {@code readPacket()} 的 {@link MissClient}。</p>
 *
 * <p>连接的关闭由 {@link CameraStream} 负责，实现方不要在返回后自行关闭。</p>
 */
@FunctionalInterface
public interface MissOpener {

    /**
     * 建立一条全新的上游取流连接并开始推流。
     *
     * @return 已登录、已 VideoStart 的 MISS 客户端
     * @throws IOException 协商/握手/登录/启动任一步骤失败（调用方据此退避重试）
     */
    MissClient open() throws IOException;
}
