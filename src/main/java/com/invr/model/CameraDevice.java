package com.invr.model;

/**
 * 米家摄像头设备（来自 /v2/home/device_list_page）。
 *
 * @param did     设备 ID
 * @param name    用户在米家设置的设备名称
 * @param model   型号，如 chuangmi.camera.xxx
 * @param mac     设备 MAC
 * @param localIp 局域网 IP（CS2 直连目标）
 * @param online  米家云上报的在线状态（isOnline）
 */
public record CameraDevice(
        String did,
        String name,
        String model,
        String mac,
        String localIp,
        boolean online
) {
}
