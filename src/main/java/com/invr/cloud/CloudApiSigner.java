package com.invr.cloud;

import com.invr.crypto.Rc4Cipher;
import com.invr.util.CryptoUtils;
import okhttp3.FormBody;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 米家业务 API 请求签名与响应解密。
 *
 * <p>对应 go2rtc pkg/xiaomi/cloud.go 的 Request()：
 * <ol>
 *   <li>nonce = 8 随机 + BE32(unix秒/60)，signedNonce = SHA256(ssecurity ‖ nonce)；</li>
 *   <li>rc4_hash__ = b64(SHA1("POST&<path>&data=<json>&" + b64(signedNonce)))；</li>
 *   <li>用 RC4(signedNonce) 加密 data 与 rc4_hash__（RC4 初始化后先丢 1024 字节）；</li>
 *   <li>signature = b64(SHA1("POST&<path>&data=<encData>&rc4_hash__=<encHash>&" + b64(signedNonce)))；</li>
 *   <li>表单 data / rc4_hash__ / signature / _nonce；响应以同一 signedNonce RC4 解密。</li>
 * </ol>
 */
public class CloudApiSigner {

    private final byte[] ssecurity;
    private final SecureRandom random = new SecureRandom();

    public CloudApiSigner(String ssecurityBase64) {
        this.ssecurity = CryptoUtils.b64decode(ssecurityBase64);
    }

    /** 一次签名的表单与本次签名使用的 signedNonce（解密响应用）。 */
    public record SignedRequest(FormBody body, byte[] signedNonce) {
    }

    public SignedRequest sign(String apiURL, String dataJson) {
        byte[] nonce = newNonce();
        return sign(apiURL, dataJson, nonce);
    }

    /** 包可见：允许测试注入固定 nonce。 */
    SignedRequest sign(String apiURL, String dataJson, byte[] nonce) {
        if (nonce.length != 12) {
            throw new IllegalArgumentException("nonce 必须 12 字节");
        }
        byte[] signedNonce = CryptoUtils.sha256(CryptoUtils.concat(ssecurity, nonce));
        String b64SignedNonce = CryptoUtils.b64(signedNonce);

        String rc4Hash = CryptoUtils.b64(CryptoUtils.sha1(
                ("POST&" + apiURL + "&data=" + dataJson + "&" + b64SignedNonce)
                        .getBytes(StandardCharsets.UTF_8)));

        String encData = CryptoUtils.b64(Rc4Cipher.crypt(signedNonce, dataJson.getBytes(StandardCharsets.UTF_8)));
        String encHash = CryptoUtils.b64(Rc4Cipher.crypt(signedNonce, rc4Hash.getBytes(StandardCharsets.UTF_8)));

        String signature = CryptoUtils.b64(CryptoUtils.sha1(
                ("POST&" + apiURL + "&data=" + encData + "&rc4_hash__=" + encHash + "&" + b64SignedNonce)
                        .getBytes(StandardCharsets.UTF_8)));

        FormBody form = new FormBody.Builder()
                .add("data", encData)
                .add("rc4_hash__", encHash)
                .add("signature", signature)
                .add("_nonce", CryptoUtils.b64(nonce))
                .build();
        return new SignedRequest(form, signedNonce);
    }

    /** 响应体 base64 → RC4(signedNonce) 解密 → JSON 字符串。 */
    public String decryptResponse(byte[] signedNonce, String responseBodyBase64) {
        byte[] encrypted = Base64.getDecoder().decode(responseBodyBase64.trim());
        return new String(Rc4Cipher.crypt(signedNonce, encrypted), StandardCharsets.UTF_8);
    }

    private byte[] newNonce() {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        // 后 4 字节为大端 unix 秒 / 60
        ByteBuffer.wrap(nonce, 8, 4).putInt((int) (System.currentTimeMillis() / 1000 / 60));
        return nonce;
    }
}
