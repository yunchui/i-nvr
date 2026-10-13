package com.invr.crypto;

/**
 * HSalsa20 核心函数（DJB NaCl / Go x/crypto/salsa20/salsa.HSalsa20）。
 *
 * <p>用于从 X25519 原始共享密钥派生小米 MISS 会话密钥：</p>
 * <pre>
 *   shared = HSalsa20(nonce=16字节0, key=X25519(clientPriv, devicePub), sigma="expand 32-byte k")
 * </pre>
 *
 * <p>这正是 Go {@code nacl/box.Precompute} 的完整行为：
 * {@code ScalarMult} 之后再做一次 {@code salsa.HSalsa20(out, zeros, out, Sigma)}。
 * 输出为 8 个对角线/中轴 word（0,5,10,15,6,7,8,9）的小端编码，共 32 字节，
 * 且不与初始状态相加（区别于 Salsa20 分组函数）。</p>
 */
public final class HSalsa20 {

    /** "expand 32-byte k"，4 个小端常量 word。 */
    private static final int SIGMA_0 = 0x61707865;
    private static final int SIGMA_1 = 0x3320646e;
    private static final int SIGMA_2 = 0x79622d32;
    private static final int SIGMA_3 = 0x6b206574;

    private HSalsa20() {
    }

    /**
     * 计算 HSalsa20 输出。
     *
     * @param key32  32 字节密钥（X25519 原始共享密钥）
     * @param nonce16 16 字节 nonce（小米协议固定全 0）
     * @return 32 字节派生密钥
     */
    public static byte[] hsalsa20(byte[] key32, byte[] nonce16) {
        if (key32 == null || key32.length != 32) {
            throw new IllegalArgumentException("key 必须为 32 字节");
        }
        if (nonce16 == null || nonce16.length != 16) {
            throw new IllegalArgumentException("nonce 必须为 16 字节");
        }

        int[] k = new int[8];
        for (int i = 0; i < 8; i++) {
            k[i] = le32(key32, i * 4);
        }
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            n[i] = le32(nonce16, i * 4);
        }

        int[] x = new int[]{
                SIGMA_0, k[0], k[1], k[2],
                k[3], SIGMA_1, n[0], n[1],
                n[2], n[3], SIGMA_2, k[4],
                k[5], k[6], k[7], SIGMA_3
        };

        for (int i = 0; i < 10; i++) {
            // column round
            quarterRound(x, 0, 4, 8, 12);
            quarterRound(x, 5, 9, 13, 1);
            quarterRound(x, 10, 14, 2, 6);
            quarterRound(x, 15, 3, 7, 11);
            // row round
            quarterRound(x, 0, 1, 2, 3);
            quarterRound(x, 5, 6, 7, 4);
            quarterRound(x, 10, 11, 8, 9);
            quarterRound(x, 15, 12, 13, 14);
        }

        int[] outWords = new int[]{x[0], x[5], x[10], x[15], x[6], x[7], x[8], x[9]};
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            putLe32(out, i * 4, outWords[i]);
        }
        return out;
    }

    /** 便捷方法：以全 0 nonce 派生（小米 MISS 场景）。 */
    public static byte[] derive(byte[] key32) {
        return hsalsa20(key32, new byte[16]);
    }

    private static void quarterRound(int[] x, int a, int b, int c, int d) {
        x[b] ^= Integer.rotateLeft(x[a] + x[d], 7);
        x[c] ^= Integer.rotateLeft(x[b] + x[a], 9);
        x[d] ^= Integer.rotateLeft(x[c] + x[b], 13);
        x[a] ^= Integer.rotateLeft(x[d] + x[c], 18);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }
}
