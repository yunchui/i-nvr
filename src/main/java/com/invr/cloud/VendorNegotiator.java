package com.invr.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.invr.crypto.X25519Keys;
import com.invr.model.CameraDevice;
import com.invr.model.P2pConnectParams;
import com.invr.util.CryptoUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * miss 取流厂商协商：本地生成 X25519 公钥上报云端，换取设备公钥、签名与 CS2 参数。
 *
 * <p>每次新建 CS2 连接都必须重新协商（云端下发一次性签名/密钥参数）。
 */
public class VendorNegotiator {

    private static final String API_MISS_GET_VENDOR = "/v2/device/miss_get_vendor";

    /** vendor 枚举：1=tutk、3=agora、4=cs2、6=mtp；本项目仅接受 4。 */
    public static final int VENDOR_CS2 = 4;

    private final MiCloudApi api;

    public VendorNegotiator(MiCloudApi api) {
        this.api = api;
    }

    /**
     * 协商一台设备的 CS2 连接参数。
     *
     * @throws MiCloudException vendor 非 cs2 或云端未返回公钥/签名
     */
    public P2pConnectParams negotiate(CameraDevice device) {
        // 1. 本地 X25519 密钥对
        X25519Keys.KeyPair keyPair = X25519Keys.generate();
        String clientPublicHex = CryptoUtils.hex(keyPair.publicKey());

        // 2. miss_get_vendor
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("app_pubkey", clientPublicHex);
        params.put("did", device.did());
        params.put("support_vendors", "TUTK_CS2_MTP");
        JsonNode result = api.call(API_MISS_GET_VENDOR, params);

        int vendor = result.path("vendor").path("vendor").asInt(-1);
        String p2pId = result.path("vendor").path("vendor_params").path("p2p_id").asText("");
        String devicePublicHex = result.path("public_key").asText("");
        String sign = result.path("sign").asText("");

        // 3. 仅接受 cs2；其余型号本项目明确不支持
        if (vendor != VENDOR_CS2) {
            throw new MiCloudException("设备 " + device.model() + " 返回 vendor=" + vendor
                    + "，非 miss/CS2（4），不在本项目支持范围（仅支持 2020 年后 miss/cs2 设备）");
        }
        if (devicePublicHex.isEmpty() || sign.isEmpty()) {
            throw new MiCloudException("miss_get_vendor 未返回 public_key/sign：" + result);
        }

        return new P2pConnectParams(
                device.did(),
                device.model(),
                device.localIp(),
                vendor,
                p2pId,
                clientPublicHex,
                CryptoUtils.hex(keyPair.privateKey()),
                devicePublicHex,
                sign);
    }
}
