package com.invr.p2p.cs2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * CS2 握手（1:1 对照 go2rtc handshake）。
 *
 * <pre>
 *   UDP host:32108
 *     1. 周期发 LAN_SEARCH [F1,30,00,00]，直到收到 PUNCH(0x41)（更新对端端口）
 *     2. 原样回发 0x41，直到收到 RDY_UDP(0x42) 或 RDY_TCP(0x43)
 *     3a. 0x42：沿用 UDP
 *     3b. 0x43：关 UDP，对“最后一次回包的对端地址（IP+新端口）”发起 TCP 连接
 * </pre>
 */
public final class Cs2Dialer {

    private static final Logger log = LoggerFactory.getLogger(Cs2Dialer.class);

    private Cs2Dialer() {
    }

    /**
     * @param host      设备局域网 IP
     * @param transport ""=由设备决定、"udp"、"tcp"
     */
    public static Cs2Connection dial(String host, String transport) throws IOException {
        boolean wantUdp = transport == null || transport.isEmpty() || "udp".equalsIgnoreCase(transport);
        boolean wantTcp = transport == null || transport.isEmpty() || "tcp".equalsIgnoreCase(transport);

        UdpCs2Transport udp = new UdpCs2Transport(host, Cs2.DEVICE_PORT);
        log.info("CS2 握手开始：目标 {}:{}，本地 UDP 端口 {}",
                host, Cs2.DEVICE_PORT, udp.localPort());

        byte[] lanSearch = {(byte) Cs2.MAGIC, (byte) Cs2.MSG_LAN_SEARCH, 0, 0};
        byte[] res = udp.writeUntil(lanSearch,
                p -> (p[1] & 0xFF) == Cs2.MSG_PUNCH_PKT,
                Cs2.HANDSHAKE_TIMEOUT_MS);
        log.info("收到 PUNCH(0x41) 来自 {}:{}", udp.peerIp().getHostAddress(), udp.peerPort());

        byte[] punch = res;
        res = udp.writeUntil(punch, p -> {
            int type = p[1] & 0xFF;
            return (wantUdp && type == Cs2.MSG_P2P_RDY_UDP)
                    || (wantTcp && type == Cs2.MSG_P2P_RDY_TCP);
        }, Cs2.HANDSHAKE_TIMEOUT_MS);

        Cs2Transport chosen;
        boolean isTcp;
        int type = res[1] & 0xFF;
        if (type == Cs2.MSG_P2P_RDY_TCP) {
            // TCP 目标为握手期间更新后的对端地址（IP + 设备回话的新端口）
            java.net.InetAddress ip = udp.peerIp();
            int port = udp.peerPort();
            log.info("收到 RDY_TCP(0x43)，改为 TCP 连接 {}:{}", ip.getHostAddress(), port);
            udp.close();
            chosen = new TcpCs2Transport(ip, port);
            isTcp = true;
        } else {
            log.info("收到 RDY_UDP(0x42)，沿用 UDP（对端端口 {}）", udp.peerPort());
            udp.setReadTimeout(0);
            chosen = udp;
            isTcp = false;
        }

        Cs2Connection connection = new Cs2Connection(chosen, isTcp);
        connection.start();
        log.info("CS2 传输通道已建立（{}）", connection.protocol());
        return connection;
    }
}
