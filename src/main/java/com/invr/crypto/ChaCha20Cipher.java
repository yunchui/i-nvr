package com.invr.crypto;

import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * MISS 会话媒体流/业务命令使用的 ChaCha20（RFC 8439 底层流密码，不带 Poly1305 认证）。
 *
 * <p>对应 go2rtc pkg/xiaomi/crypto/crypto.go：
 * <ul>
 *   <li>12 字节 nonce 布局 = 4 字节 0x00 + 8 字节随机 nonce；</li>
 *   <li>密文格式 = 8 字节 nonce 前缀 + 密文。</li>
 * </ul>
 */
public final class ChaCha20Cipher {

    /** 随机 nonce 部分长度。 */
    public static final int NONCE_PREFIX_LEN = 8;
    /** ChaCha20-IETF 完整 nonce 长度。 */
    public static final int NONCE_FULL_LEN = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ChaCha20Cipher() {
    }

    /**
     * 加密：输出 8 字节随机 nonce + 密文。
     */
    public static byte[] encode(byte[] key, byte[] plain) {
        checkKey(key);
        byte[] nonce8 = new byte[NONCE_PREFIX_LEN];
        RANDOM.nextBytes(nonce8);
        byte[] cipher = process(key, nonce8, plain);
        byte[] out = new byte[NONCE_PREFIX_LEN + cipher.length];
        System.arraycopy(nonce8, 0, out, 0, NONCE_PREFIX_LEN);
        System.arraycopy(cipher, 0, out, NONCE_PREFIX_LEN, cipher.length);
        return out;
    }

    /**
     * 解密：输入 8 字节 nonce + 密文，输出明文。
     */
    public static byte[] decode(byte[] key, byte[] data) {
        checkKey(key);
        if (data == null || data.length <= NONCE_PREFIX_LEN) {
            throw new IllegalArgumentException("密文长度不足，缺少 nonce 前缀");
        }
        byte[] nonce8 = Arrays.copyOfRange(data, 0, NONCE_PREFIX_LEN);
        byte[] cipher = Arrays.copyOfRange(data, NONCE_PREFIX_LEN, data.length);
        return process(key, nonce8, cipher);
    }

    private static byte[] process(byte[] key, byte[] nonce8, byte[] in) {
        byte[] nonce = new byte[NONCE_FULL_LEN];
        System.arraycopy(nonce8, 0, nonce, 4, NONCE_PREFIX_LEN);
        ChaCha7539Engine engine = new ChaCha7539Engine();
        engine.init(true, new ParametersWithIV(new KeyParameter(key), nonce));
        byte[] out = new byte[in.length];
        engine.processBytes(in, 0, in.length, out, 0);
        return out;
    }

    private static void checkKey(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("ChaCha20 key 必须为 32 字节");
        }
    }
}
