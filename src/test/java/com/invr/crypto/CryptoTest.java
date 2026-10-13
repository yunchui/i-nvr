package com.invr.crypto;

import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.engines.RC4Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 加密原语测试。
 *
 * <ul>
 *   <li>RC4：RFC 6229 已知向量 + 米家云"丢弃前 1024 字节"用法。</li>
 *   <li>X25519：RFC 7748 §6.1 Alice/Bob 主向量与 basepoint 公钥、双向 ECDH 自洽。</li>
 *   <li>HSalsa20 + ChaCha20：以 go2rtc v1.9.14 真机/参考设备对拍抓到的完整 VideoStart
 *       帧做端到端互通回归（X25519 → HSalsa20 派生 → ChaCha20 解出明文命令）。</li>
 *   <li>ChaCha20：RFC 8439 §2.3.2 全零 key/nonce、counter=0 keystream 向量。</li>
 * </ul>
 */
class CryptoTest {

    @Test
    void rc4Rfc6229Vector() {
        // Key="Key"，Plaintext="Plaintext" → BBF316E8D940AF0AD3（原生 RC4，不丢弃）
        byte[] key = "Key".getBytes();
        byte[] plain = "Plaintext".getBytes();
        RC4Engine engine = new RC4Engine();
        engine.init(true, new KeyParameter(key));
        byte[] out = new byte[plain.length];
        engine.processBytes(plain, 0, plain.length, out, 0);
        assertArrayEquals(unhex("BBF316E8D940AF0AD3"), out);
    }

    @Test
    void rc4Discard1024MatchesManual() {
        byte[] key = "0123456789abcdef".getBytes();
        byte[] data = "hello-xiaomi-cloud-api".getBytes();

        // 手工实现：新建引擎、丢弃 1024、再加密
        RC4Engine engine = new RC4Engine();
        engine.init(true, new KeyParameter(key));
        byte[] discard = new byte[1024];
        engine.processBytes(discard, 0, 1024, discard, 0);
        byte[] expected = new byte[data.length];
        engine.processBytes(data, 0, data.length, expected, 0);

        assertArrayEquals(expected, Rc4Cipher.crypt(key, data));
        // 丢弃版与不丢弃版结果应不同
        RC4Engine raw = new RC4Engine();
        raw.init(true, new KeyParameter(key));
        byte[] rawOut = new byte[data.length];
        raw.processBytes(data, 0, data.length, rawOut, 0);
        assertNotEquals(Arrays.toString(rawOut), Arrays.toString(expected));
    }

    @Test
    void x25519BothSidesAgree() {
        X25519Keys.KeyPair a = X25519Keys.generate();
        X25519Keys.KeyPair b = X25519Keys.generate();
        byte[] aShared = X25519Keys.sharedSecret(a.privateKey(), b.publicKey());
        byte[] bShared = X25519Keys.sharedSecret(b.privateKey(), a.publicKey());
        assertArrayEquals(aShared, bShared);
        assertEquals(32, aShared.length);
    }

    @Test
    void x25519Rfc7748Vector() {
        // RFC 7748 §6.1 Alice/Bob 主向量：X25519(Alice 私钥, Bob 公钥) = K
        byte[] alicePriv = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] bobPriv = unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        byte[] alicePub = unhex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a");
        byte[] bobPub = unhex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f");
        byte[] sharedK = unhex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");

        // ECDH 两个方向都等于 RFC K
        assertArrayEquals(sharedK, X25519Keys.sharedSecret(alicePriv, bobPub));
        assertArrayEquals(sharedK, X25519Keys.sharedSecret(bobPriv, alicePub));

        // X25519(Alice 私钥, basepoint u=9) = Alice 公钥
        byte[] basePoint = new byte[32];
        basePoint[0] = 0x09;
        assertArrayEquals(alicePub, X25519Keys.sharedSecret(alicePriv, basePoint));
        assertArrayEquals(bobPub, X25519Keys.sharedSecret(bobPriv, basePoint));
    }

    @Test
    void missSharedKeyDerivationMatchesGoBoxPrecompute() {
        // go2rtc 参考设备对拍：设备=RFC Alice，客户端=RFC Bob。
        // Go nacl/box.Precompute = X25519 后再 HSalsa20(zeros, shared, sigma)。
        byte[] alicePriv = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] bobPriv = unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        byte[] alicePub = unhex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a");
        byte[] bobPub = unhex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f");

        // 裸 X25519 结果（HSalsa20 之前）
        byte[] rawK = unhex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");
        assertArrayEquals(rawK, X25519Keys.sharedSecret(bobPriv, alicePub));
        assertArrayEquals(rawK, X25519Keys.sharedSecret(alicePriv, bobPub));

        // 派生后的会话密钥（客户端方向与设备方向必须一致）
        byte[] expectedKey = unhex("1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389");
        assertArrayEquals(expectedKey, X25519Keys.calcSharedKey(bobPriv, alicePub));
        assertArrayEquals(expectedKey, X25519Keys.calcSharedKey(alicePriv, bobPub));
    }

    @Test
    void decodesGo2rtcVideoStartFrame() {
        // go2rtc v1.9.14 实际发出的 VideoStart(0x102) 加密帧（参考设备对拍抓取，完整 46B body）：
        //   body = 8B nonce + 38B 密文；明文 = BE32(0x102) + {"videoquality":2,"enableaudio":1}
        byte[] bobPriv = unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        byte[] alicePub = unhex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a");
        byte[] sessionKey = X25519Keys.calcSharedKey(bobPriv, alicePub);

        byte[] body = unhex("39ac5f7553350942"
                + "517305fd32ef57030b367cf2130e5f67c089b92b4d4c32834923563f69e57f22f62740d390f6");
        byte[] plain = ChaCha20Cipher.decode(sessionKey, body);

        // 期望明文 = BE32(0x102) + JSON
        byte[] cmd = new byte[]{0x00, 0x00, 0x01, 0x02};
        byte[] json = "{\"videoquality\":2,\"enableaudio\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[cmd.length + json.length];
        System.arraycopy(cmd, 0, expected, 0, cmd.length);
        System.arraycopy(json, 0, expected, cmd.length, json.length);

        assertArrayEquals(expected, plain);
    }

    @Test
    void chacha20RoundTrip() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) i;
        }
        byte[] plain = "miss media payload H264/H265".getBytes();
        byte[] encoded = ChaCha20Cipher.encode(key, plain);
        // 密文 = 8 字节 nonce 前缀 + 密文
        assertEquals(8 + plain.length, encoded.length);
        byte[] decoded = ChaCha20Cipher.decode(key, encoded);
        assertArrayEquals(plain, decoded);
    }

    @Test
    void chacha20Rfc8439ZeroVector() {
        // RFC 8439 §2.3.2：全零 key、全零 12B nonce、counter=0，
        // 加密全零明文（即 keystream）前 16 字节：76 b8 e0 ad a0 f1 3d 90 40 5d 6a e5 53 86 bd 28
        byte[] key = new byte[32];
        byte[] packet = new byte[8 + 64];
        byte[] keystream = ChaCha20Cipher.decode(key, packet);
        assertArrayEquals(unhex("76B8E0ADA0F13D90405D6AE55386BD28"),
                Arrays.copyOfRange(keystream, 0, 16));

        // 直接用 BC 引擎、12B 全零 nonce 对照
        ChaCha7539Engine engine = new ChaCha7539Engine();
        engine.init(true, new ParametersWithIV(new KeyParameter(key), new byte[12]));
        byte[] direct = new byte[16];
        engine.processBytes(new byte[16], 0, 16, direct, 0);
        assertArrayEquals(unhex("76B8E0ADA0F13D90405D6AE55386BD28"), direct);
    }

    private static byte[] unhex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
