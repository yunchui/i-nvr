package com.invr.hub;

import com.invr.model.CameraDevice;

/**
 * 摄像头接入连接器：把"按 DID 找设备"与"为设备开一条上游"从具体前端（RTSP / HLS / 录像 / CLI）
 * 中解耦。所有下行消费者通过同一实现拿到 {@link MissOpener}，从而都汇入同一个 {@link StreamHub}，
 * 保证同一摄像头无论被多少消费者使用都只占一条小米 CS2 上游。
 */
public interface CameraConnector {

    /** 按 DID 查找当前账号下的摄像头；未发现（未登录/不在线/不在列表）返回 {@code null}。 */
    CameraDevice findDevice(String did);

    /** 为指定设备构造一个上游开启器（每次重连由 StreamHub 回调，内部重新云端协商）。 */
    MissOpener openerFor(CameraDevice device);
}
