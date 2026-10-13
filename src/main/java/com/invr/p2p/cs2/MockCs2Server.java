package com.invr.p2p.cs2;

import com.invr.crypto.ChaCha20Cipher;
import com.invr.crypto.X25519Keys;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 CS2“参考设备”，用于跨实现加密互通对拍与 StreamHub 自动重连的确定性验证。
 *
 * <p>在固定的 32108 UDP 端口上反复响应打洞：每接受一条 TCP 连接，完成握手、回 AuthRes、
 * 解密客户端 VideoStart，然后保持 {@link #SESSION_LIFETIME_MS} 毫秒（期间应答 PING），
 * 随后<b>主动断开该连接</b>模拟设备/网络故障，再回到等待下一次 LAN_SEARCH。
 * 客户端的 StreamHub 应据此自动重新协商/握手/登录/VideoStart，generation 逐次递增。</p>
 */
public final class MockCs2Server {

    private static final Logger log = LoggerFactory.getLogger("MOCK");
    private static final Pattern PUB_KEY = Pattern.compile("\"public_key\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private static final int CMD_AUTH_REQ = 0x100;
    private static final int CMD_ENCODED = 0x1001;

    /** 每条 mock 连接存活时长，到点主动断开，模拟断流。 */
    private static final long SESSION_LIFETIME_MS = 5_000;

    private MockCs2Server() {
    }

    public static void run(int durationSeconds) throws Exception {
        InetAddress loop = InetAddress.getByName("127.0.0.1");

        // 设备侧 X25519 密钥对（RFC 7748 Alice，配对与共享密钥均由权威向量保证）
        byte[] devPriv = Hex.decode("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] devPub = Hex.decode("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a");
        X25519Keys.KeyPair device = new X25519Keys.KeyPair(devPub, devPriv);
        log.info("mock 设备 device_public = {}", Hex.toHexString(device.publicKey()));

        DatagramSocket mainUdp = new DatagramSocket(new InetSocketAddress(loop, Cs2.DEVICE_PORT));
        mainUdp.setReuseAddress(true);
        log.info("mock UDP 持续监听 127.0.0.1:{}（每 {}ms 主动断开一条连接模拟断流）",
                Cs2.DEVICE_PORT, SESSION_LIFETIME_MS);

        long overallDeadline = System.currentTimeMillis() + durationSeconds * 1000L;
        int sessionIndex = 0;
        try {
            while (System.currentTimeMillis() < overallDeadline) {
                mainUdp.setSoTimeout(1_000);
                byte[] buf = new byte[1200];
                DatagramPacket lan = new DatagramPacket(buf, buf.length);
                try {
                    mainUdp.receive(lan);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                int clientPort = lan.getPort();
                sessionIndex++;
                log.info("========== 第 {} 次连接：收到 LAN_SEARCH from 127.0.0.1:{} ==========",
                        sessionIndex, clientPort);

                DatagramSocket punch = new DatagramSocket(new InetSocketAddress(loop, 0));
                int p = punch.getLocalPort();
                ServerSocket tcpSrv = new ServerSocket();
                tcpSrv.setReuseAddress(true);
                tcpSrv.bind(new InetSocketAddress(loop, p));

                byte[] punchPkt = new byte[16];
                punchPkt[0] = (byte) Cs2.MAGIC;
                punchPkt[1] = (byte) Cs2.MSG_PUNCH_PKT;
                punch.send(new DatagramPacket(punchPkt, punchPkt.length, loop, clientPort));

                byte[] buf2 = new byte[1200];
                DatagramPacket echo = new DatagramPacket(buf2, buf2.length);
                punch.setSoTimeout(5_000);
                punch.receive(echo);

                byte[] rdy = new byte[16];
                rdy[0] = (byte) Cs2.MAGIC;
                rdy[1] = (byte) Cs2.MSG_P2P_RDY_TCP;
                punch.send(new DatagramPacket(rdy, rdy.length, loop, echo.getPort()));

                tcpSrv.setSoTimeout(5_000);
                Socket sock = tcpSrv.accept();
                punch.close();
                log.info("第 {} 次连接 TCP 已接入（对端临时端口 {}）", sessionIndex, sock.getPort());

                long sessionDeadline = Math.min(
                        System.currentTimeMillis() + SESSION_LIFETIME_MS, overallDeadline);
                try {
                    handleSession(sock, device, sessionDeadline, sessionIndex);
                } finally {
                    sock.close();
                    tcpSrv.close();
                }
                log.info("========== mock 主动断开第 {} 次连接（模拟断流）==========", sessionIndex);
            }
        } finally {
            mainUdp.close();
            log.info("mock 结束（共接受 {} 次连接）", sessionIndex);
        }
    }

    private static void handleSession(Socket sock, X25519Keys.KeyPair device,
                                      long sessionDeadline, int sessionIndex) throws IOException {
        InputStream in = sock.getInputStream();
        OutputStream out = sock.getOutputStream();
        sock.setSoTimeout(1_000);
        byte[] sharedKey = null;
        int videoStarts = 0;

        while (System.currentTimeMillis() < sessionDeadline) {
            byte[] hdr = new byte[8];
            int got;
            try {
                got = readFully(in, hdr, 0, 8);
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (got < 0) {
                break;
            }
            if (got < 8) {
                break;
            }
            int n = ((hdr[0] & 0xFF) << 8) | (hdr[1] & 0xFF);
            byte[] frame = new byte[n];
            if (readFully(in, frame, 0, n) < n) {
                break;
            }
            if (n < 2) {
                continue;
            }
            int type = frame[1] & 0xFF;
            if (type == Cs2.MSG_PING) {
                sendInner(out, new byte[]{(byte) Cs2.MAGIC, (byte) Cs2.MSG_PONG, 0, 0});
                continue;
            }
            if (type != Cs2.MSG_DRW || n < 16) {
                continue;
            }
            int channel = frame[5] & 0xFF;
            if (channel != Cs2.CHANNEL_CMD) {
                continue;
            }
            long cmd = ((long) (frame[12] & 0xFF) << 24) | ((frame[13] & 0xFF) << 16)
                    | ((frame[14] & 0xFF) << 8) | (frame[15] & 0xFF);
            byte[] payload = Arrays.copyOfRange(frame, 16, n);

            if (cmd == CMD_AUTH_REQ) {
                String json = new String(payload, StandardCharsets.UTF_8);
                Matcher m = PUB_KEY.matcher(json);
                if (m.find()) {
                    byte[] clientPub = Hex.decode(m.group(1));
                    sharedKey = X25519Keys.calcSharedKey(device.privateKey(), clientPub);
                    log.info("[会话{}] 收到 AuthReq，推导 sharedKey={}", sessionIndex, Hex.toHexString(sharedKey));
                }
                byte[] resJson = "{\"result\":\"success\"}".getBytes(StandardCharsets.UTF_8);
                byte[] inner = Cs2Connection.marshalCmd(Cs2.CHANNEL_CMD, 0, 0x101, resJson);
                sendInner(out, inner);
                log.info("[会话{}] 已回 AuthRes(0x101) success", sessionIndex);
            } else if (cmd == CMD_ENCODED) {
                if (sharedKey == null) {
                    continue;
                }
                byte[] plain = ChaCha20Cipher.decode(sharedKey, payload);
                long innerCmd = ((long) (plain[0] & 0xFF) << 24) | ((plain[1] & 0xFF) << 16)
                        | ((plain[2] & 0xFF) << 8) | (plain[3] & 0xFF);
                String innerJson = new String(plain, 4, plain.length - 4, StandardCharsets.UTF_8);
                videoStarts++;
                log.info("[会话{}] 解密 VideoStart 成功（本会话第{}次）：cmd=0x{} JSON=[{}]",
                        sessionIndex, videoStarts, Long.toHexString(innerCmd), innerJson);
            }
        }
    }

    private static void sendInner(OutputStream out, byte[] inner) throws IOException {
        byte[] frame = new byte[8 + inner.length];
        frame[0] = (byte) ((inner.length >>> 8) & 0xFF);
        frame[1] = (byte) (inner.length & 0xFF);
        frame[2] = (byte) Cs2.MAGIC_TCP;
        System.arraycopy(inner, 0, frame, 8, inner.length);
        out.write(frame);
        out.flush();
    }

    private static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(b, off + total, len - total);
            if (r < 0) {
                return total > 0 ? total : -1;
            }
            total += r;
        }
        return total;
    }
}
