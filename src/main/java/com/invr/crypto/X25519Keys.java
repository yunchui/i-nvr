package com.invr.crypto;

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.math.ec.rfc7748.X25519;

import java.security.SecureRandom;

/**
 * X25519（等价 NaCl box / Curve25519）密钥对与 ECDH 共享密钥。
 *
 * <p>对应 go2rtc pkg/xiaomi/crypto/crypto.go：
 * 客户端密钥对用于 miss_get_vendor 协商；共享密钥 = X25519(clientPrivate, devicePublic)，
 * 作为 MISS 会话 ChaCha20 的密钥。
 *
 * <p>注意：必须直接使用 BC 底层 {@link X25519#scalarMult}（RFC 7748，内部对 scalar 做
 * clamp）；高层 X25519Agreement 在 BC 1.78 上对原始 scalar 的结果与 RFC 7748 测试向量不符，
 * 会导致与 Go nacl/box.Precompute 算出的共享密钥不一致。
 */
public final class X25519Keys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private X25519Keys() {
    }

    /** 32 字节公钥 + 32 字节私钥（私钥为未 clamp 的原始 scalar，使用时由 X25519 内部 clamp）。 */
    public record KeyPair(byte[] publicKey, byte[] privateKey) {
    }

    /** 生成新的 X25519 密钥对。 */
    public static KeyPair generate() {
        X25519PrivateKeyParameters privateKey = new X25519PrivateKeyParameters(RANDOM);
        return new KeyPair(privateKey.generatePublicKey().getEncoded(), privateKey.getEncoded());
    }

    /**
     * 计算 X25519 原始共享密钥（ECDH），对应 Go curve25519.ScalarMult。
     *
     * @param privateKey       本端 32 字节私钥（原始 scalar，内部会 clamp）
     * @param otherPublicKey   对端 32 字节公钥（设备公钥来自 miss_get_vendor 的 public_key）
     * @return 32 字节原始共享密钥（尚未派生，不能直接当 ChaCha20 密钥）
     */
    public static byte[] sharedSecret(byte[] privateKey, byte[] otherPublicKey) {
        if (privateKey == null || privateKey.length != 32) {
            throw new IllegalArgumentException("privateKey 必须为 32 字节");
        }
        if (otherPublicKey == null || otherPublicKey.length != 32) {
            throw new IllegalArgumentException("otherPublicKey 必须为 32 字节");
        }
        byte[] secret = new byte[32];
        // RFC 7748 scalarMult：内部 decodeScalar 做 clamp（k[0]&=248; k[31]&=127; k[31]|=64）
        X25519.scalarMult(privateKey, 0, otherPublicKey, 0, secret, 0);
        return secret;
    }

    /**
     * 计算小米 MISS 会话 ChaCha20 密钥，严格等价 Go
     * {@code crypto.CalcSharedKey → nacl/box.Precompute}：
     * <pre>
     *   raw = X25519(clientPrivate, devicePublic)
     *   key = HSalsa20(nonce=0, raw, sigma="expand 32-byte k")
     * </pre>
     * 注意 box.Precompute 在 ScalarMult 之后还做了一次 HSalsa20 派生，
     * 直接使用 {@link #sharedSecret} 的裸 X25519 结果会导致加解密互通失败。
     */
    public static byte[] calcSharedKey(byte[] clientPrivate, byte[] devicePublic) {
        return HSalsa20.derive(sharedSecret(clientPrivate, devicePublic));
    }
}
