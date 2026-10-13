package com.invr.crypto;

import org.bouncycastle.crypto.engines.RC4Engine;
import org.bouncycastle.crypto.params.KeyParameter;

import java.nio.charset.StandardCharsets;

/**
 * 米家云业务 API 使用的 RC4 加解密。
 *
 * <p>对应 go2rtc pkg/xiaomi/cloud.go 的 {@code crypt()}：每次新建 RC4 实例后，
 * 先对 1024 字节零缓冲做一次 XOR 并丢弃，再处理真实数据。
 */
public final class Rc4Cipher {

    /** 与 go2rtc 一致：初始化后丢弃的密钥流字节数。 */
    public static final int DISCARD_BYTES = 1024;

    private Rc4Cipher() {
    }

    /**
     * RC4 加解密（对称，同一方法双向使用）。
     *
     * @param key  密钥（云端签名场景为 signedNonce）
     * @param data 明文或密文
     * @return 输出与输入等长
     */
    public static byte[] crypt(byte[] key, byte[] data) {
        RC4Engine engine = new RC4Engine();
        engine.init(true, new KeyParameter(key));
        // 丢弃前 1024 字节密钥流
        engine.processBytes(new byte[DISCARD_BYTES], 0, DISCARD_BYTES, new byte[DISCARD_BYTES], 0);
        byte[] out = new byte[data.length];
        engine.processBytes(data, 0, data.length, out, 0);
        return out;
    }

    /** 字符串便捷方法（UTF-8）。 */
    public static String cryptString(byte[] key, String data) {
        return new String(crypt(key, data.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }
}
