package com.invr.miss;

import com.invr.crypto.ChaCha20Cipher;
import com.invr.crypto.X25519Keys;
import com.invr.model.P2pConnectParams;
import com.invr.p2p.cs2.Cs2Connection;
import com.invr.p2p.cs2.Cs2Dialer;
import com.invr.util.CryptoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * MISS 会话：CS2 连接之上的登录握手、加密命令与媒体包读取。
 *
 * <p>1:1 对照 go2rtc pkg/xiaomi/miss/client.go。
 */
public final class MissClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MissClient.class);

    private final Cs2Connection conn;
    private final byte[] sharedKey;
    private final String model;

    private MissClient(Cs2Connection conn, byte[] sharedKey, String model) {
        this.conn = conn;
        this.sharedKey = sharedKey;
        this.model = model;
    }

    /**
     * 建立 CS2 连接并完成 MISS 登录。
     *
     * @param params    miss_get_vendor 协商结果
     * @param transport ""=设备决定、"udp"、"tcp"
     */
    public static MissClient connect(P2pConnectParams params, String transport) throws IOException {
        Cs2Connection conn = Cs2Dialer.dial(params.host(), transport);

        byte[] clientPrivate = CryptoUtils.unhex(params.clientPrivateHex());
        byte[] devicePublic = CryptoUtils.unhex(params.devicePublicHex());
        byte[] sharedKey = X25519Keys.calcSharedKey(clientPrivate, devicePublic);

        try {
            login(conn, params.clientPublicHex(), params.sign());
        } catch (IOException e) {
            conn.close();
            throw e;
        }
        log.info("MISS 会话登录成功（model={}）", params.model());
        return new MissClient(conn, sharedKey, params.model());
    }

    public String protocol() {
        return conn.protocol() + " / " + model;
    }

    /**
     * AuthReq 0x100 明文 JSON，等待 AuthRes 0x101 且 result=success。
     */
    private static void login(Cs2Connection conn, String clientPublicHex, String sign) throws IOException {
        String authJson = "{\"public_key\":\"" + clientPublicHex + "\",\"sign\":\"" + sign
                + "\",\"uuid\":\"\",\"support_encrypt\":0}";
        log.info("发送 MISS AuthReq(0x100)，等待设备鉴权响应...");
        conn.writeCommand(MissCommands.CMD_AUTH_REQ, authJson.getBytes(StandardCharsets.UTF_8));

        byte[] response = conn.readCommandRaw(com.invr.p2p.cs2.Cs2.LOGIN_RESPONSE_TIMEOUT_MS);
        byte[] body = Arrays.copyOfRange(response, 4, response.length);
        String text = new String(body, StandardCharsets.UTF_8);
        if (!text.contains("\"result\":\"success\"")) {
            throw new IOException("miss: 登录握手失败：" + text);
        }
    }

    /**
     * 加密命令：BE32(cmd)+body 经 ChaCha20（自带 8B nonce）后包在 0x1001 内。
     */
    private void writeEncrypted(byte[] outer) throws IOException {
        byte[] encrypted = ChaCha20Cipher.encode(sharedKey, outer);
        conn.writeCommand(MissCommands.CMD_ENCODED, encrypted);
    }

    /**
     * 开始取流。
     *
     * @param channel 镜头通道：""或"0"=主镜头；"2"=第二镜头
     * @param quality hd(默认)/sd/auto
     * @param audio   "0"关、"1"开；null 默认开
     */
    public void startMedia(String channel, String quality, String audio) throws IOException {
        String q;
        if (quality == null || quality.isEmpty() || "hd".equalsIgnoreCase(quality)) {
            q = MissCommands.defaultQuality(model);
        } else if ("sd".equalsIgnoreCase(quality)) {
            q = "1";
        } else if ("auto".equalsIgnoreCase(quality)) {
            q = "0";
        } else {
            q = quality;
        }
        String a = (audio == null || audio.isEmpty()) ? "1" : audio;

        String json;
        if (channel == null || channel.isEmpty() || "0".equals(channel)) {
            json = "{\"videoquality\":" + q + ",\"enableaudio\":" + a + "}";
        } else {
            json = "{\"videoquality\":-1,\"videoquality2\":" + q + ",\"enableaudio\":" + a + "}";
        }
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] outer = new byte[4 + jsonBytes.length];
        outer[0] = (byte) ((MissCommands.CMD_VIDEO_START >>> 24) & 0xFF);
        outer[1] = (byte) ((MissCommands.CMD_VIDEO_START >>> 16) & 0xFF);
        outer[2] = (byte) ((MissCommands.CMD_VIDEO_START >>> 8) & 0xFF);
        outer[3] = (byte) (MissCommands.CMD_VIDEO_START & 0xFF);
        System.arraycopy(jsonBytes, 0, outer, 4, jsonBytes.length);
        writeEncrypted(outer);
    }

    /**
     * 停止取流（VideoStop 0x103，无 body）。
     */
    public void stopMedia() throws IOException {
        byte[] outer = {
                (byte) ((MissCommands.CMD_VIDEO_STOP >>> 24) & 0xFF),
                (byte) ((MissCommands.CMD_VIDEO_STOP >>> 16) & 0xFF),
                (byte) ((MissCommands.CMD_VIDEO_STOP >>> 8) & 0xFF),
                (byte) (MissCommands.CMD_VIDEO_STOP & 0xFF)};
        writeEncrypted(outer);
    }

    /**
     * 读取并解密一个媒体包。
     */
    public MissPacket readPacket() throws IOException {
        byte[] message = conn.readMediaMessage();
        byte[] header = Arrays.copyOfRange(message, 0, 32);
        byte[] encryptedPayload = Arrays.copyOfRange(message, 32, message.length);

        byte[] payload = ChaCha20Cipher.decode(sharedKey, encryptedPayload);

        int codecId = le32(header, 4);
        int sequence = le32(header, 8);
        int flags = le32(header, 12);
        long timestampMs;
        if (MissCommands.usesLocalTimestamp(model)) {
            timestampMs = System.currentTimeMillis();
        } else {
            // 设备毫秒时钟只在低 32 位（header[20:24] 恒为 0xFFFFFFFF 的无关字段），按无符号读取，
            // 与 go2rtc 等价（它读 uint64 后转 RTP uint32，实际只用低 32 位、约 49 天回绕）。
            timestampMs = Integer.toUnsignedLong(le32(header, 16));
        }
        if (DUMP_PKT) {
            boolean video = codecId == MissCommands.CODEC_H264 || codecId == MissCommands.CODEC_H265;
            int n = video ? dumpVideo++ : dumpAudio++;
            if (n < 14) {
                System.err.printf("[PKT] %s codec=%d seq=%d flags=0x%08x ts=%d hdr=%s%n",
                        video ? "VIDEO" : "AUDIO", codecId, sequence, flags, timestampMs,
                        org.bouncycastle.util.encoders.Hex.toHexString(header));
            }
        }
        return new MissPacket(codecId, sequence, flags, timestampMs, payload);
    }

    /** 诊断开关：-Dinvr.dumppkt=true 时打印前若干媒体包头。 */
    private static final boolean DUMP_PKT = Boolean.getBoolean("invr.dumppkt");
    private int dumpVideo;
    private int dumpAudio;

    @Override
    public void close() throws IOException {
        conn.close();
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (long) (b[off + i] & 0xFF) << (8 * i);
        }
        return v;
    }
}
