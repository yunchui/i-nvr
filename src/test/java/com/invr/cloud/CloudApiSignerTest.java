package com.invr.cloud;

import com.invr.crypto.Rc4Cipher;
import okhttp3.FormBody;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务 API 签名器测试：表单结构、固定 nonce 确定性、响应 RC4 解密往返。
 */
class CloudApiSignerTest {

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static Map<String, String> formFields(FormBody form) {
        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i < form.size(); i++) {
            fields.put(form.name(i), form.value(i));
        }
        return fields;
    }

    private CloudApiSigner signer() {
        // 32 字节 ssecurity
        return new CloudApiSigner(b64("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] fixedNonce() {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 12; i++) {
            nonce[i] = (byte) (i + 1);
        }
        return nonce;
    }

    @Test
    void signProducesAllFourFields() {
        CloudApiSigner signer = signer();
        CloudApiSigner.SignedRequest signed =
                signer.sign("/v2/home/device_list_page", "{}", fixedNonce());
        Map<String, String> fields = formFields(signed.body());

        assertEquals(4, fields.size());
        assertTrue(fields.containsKey("data"));
        assertTrue(fields.containsKey("rc4_hash__"));
        assertTrue(fields.containsKey("signature"));
        assertEquals(b64(fixedNonce()), fields.get("_nonce"));
        assertNotNull(signed.signedNonce());
        assertEquals(32, signed.signedNonce().length);
    }

    @Test
    void signIsDeterministicForSameNonce() {
        CloudApiSigner signer = signer();
        var a = signer.sign("/v2/home/device_list_page", "{}", fixedNonce());
        var b = signer.sign("/v2/home/device_list_page", "{}", fixedNonce());
        assertEquals(formFields(a.body()), formFields(b.body()));
    }

    @Test
    void responseRoundTrip() {
        CloudApiSigner signer = signer();
        CloudApiSigner.SignedRequest signed =
                signer.sign("/v2/device/miss_get_vendor", "{\"did\":\"123\"}", fixedNonce());

        // 模拟云端：base64(RC4(signedNonce, json))
        String original = "{\"code\":0,\"message\":\"ok\",\"result\":{\"vendor\":{\"vendor\":4}}}";
        String encryptedResponse = b64(Rc4Cipher.crypt(
                signed.signedNonce(), original.getBytes(StandardCharsets.UTF_8)));

        String decrypted = signer.decryptResponse(signed.signedNonce(), encryptedResponse);
        assertEquals(original, decrypted);
    }

    @Test
    void rejectsBadNonceLength() {
        CloudApiSigner signer = signer();
        assertThrows(IllegalArgumentException.class,
                () -> signer.sign("/x", "{}", new byte[11]));
    }
}
