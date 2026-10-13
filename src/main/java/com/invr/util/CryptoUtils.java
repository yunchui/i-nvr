package com.invr.util;

import org.bouncycastle.util.encoders.Hex;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 摘要 / 编码小工具。
 */
public final class CryptoUtils {

    private CryptoUtils() {
    }

    public static byte[] sha1(byte[] data) {
        return digest("SHA-1", data);
    }

    public static byte[] sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    /** 密码哈希：大写 hex(MD5(password))，对应米家 serviceLoginAuth2 的 hash 字段。 */
    public static String md5HexUpper(String text) {
        return Hex.toHexString(digest("MD5", text.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toUpperCase();
    }

    public static String hex(byte[] data) {
        return Hex.toHexString(data);
    }

    public static byte[] unhex(String text) {
        return Hex.decode(text);
    }

    public static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] b64decode(String text) {
        return Base64.getDecoder().decode(text.trim());
    }

    public static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static byte[] digest(String algo, byte[] data) {
        try {
            return MessageDigest.getInstance(algo).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JRE 缺少 " + algo + " 算法", e);
        }
    }
}
