package com.invr.model;

/**
 * miss_get_vendor 协商结果，即一次 CS2 + MISS 连接所需的全部参数。
 *
 * <p>注意：clientPrivateHex 为敏感数据，仅允许在内存中传递给 M2 连接层，
 * 禁止打印日志或落盘。
 *
 * @param did               设备 ID
 * @param model             设备型号
 * @param host              局域网 IP
 * @param vendor            厂商标识，本项目仅接受 4=cs2
 * @param p2pId             设备 P2P ID
 * @param clientPublicHex   本次协商生成的客户端 X25519 公钥（hex）
 * @param clientPrivateHex  客户端 X25519 私钥（hex，敏感）
 * @param devicePublicHex   设备 X25519 公钥（hex，来自云端）
 * @param sign              云端下发的 MISS 登录签名
 */
public record P2pConnectParams(
        String did,
        String model,
        String host,
        int vendor,
        String p2pId,
        String clientPublicHex,
        String clientPrivateHex,
        String devicePublicHex,
        String sign
) {
}
