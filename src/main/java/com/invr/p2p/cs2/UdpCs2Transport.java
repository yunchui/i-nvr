package com.invr.p2p.cs2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * CS2 UDP 传输（1:1 对照 go2rtc udpConn）。
 *
 * <p>关键点：握手期间设备会从<b>新端口</b>回话，{@link #writeUntil} 命中期望包后
 * 必须用发送方端口更新对端端口。
 */
final class UdpCs2Transport implements Cs2Transport {

    private static final Logger log = LoggerFactory.getLogger(UdpCs2Transport.class);

    private final DatagramSocket socket;
    private final InetAddress peerIp;
    private volatile int peerPort;

    UdpCs2Transport(String host, int port) throws IOException {
        this.socket = new DatagramSocket();
        InetAddress ip;
        try {
            ip = InetAddress.getByName(host);
        } catch (Exception e) {
            throw new IOException("无法解析主机 " + host, e);
        }
        this.peerIp = ip;
        this.peerPort = port;
    }

    int localPort() {
        return socket.getLocalPort();
    }

    /**
     * 每秒重发 req，直到收到同 IP、长度 ≥16 且满足判定的回包；命中后更新对端端口。
     */
    byte[] writeUntil(byte[] req, Predicate<byte[]> ok, long totalTimeoutMs) throws IOException {
        byte[] buf = new byte[Cs2.READ_BUF_SIZE];
        long deadline = System.currentTimeMillis() + totalTimeoutMs;
        socket.setSoTimeout(Cs2.RETRY_INTERVAL_MS);
        write(req);
        while (true) {
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(dp);
            } catch (SocketTimeoutException e) {
                if (System.currentTimeMillis() >= deadline) {
                    throw new SocketTimeoutException("cs2: 握手超时（" + totalTimeoutMs
                            + "ms 内未收到设备 " + peerIp.getHostAddress() + ":" + peerPort + " 回应）");
                }
                log.debug("未收到期望回包，重发握手请求 -> {}:{}", peerIp.getHostAddress(), peerPort);
                write(req);
                continue;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new SocketTimeoutException("cs2: 握手超时");
            }
            // 仅接受同 IP 且长度 >=16 的包
            if (!sameIp(dp.getAddress()) || dp.getLength() < 16) {
                continue;
            }
            byte[] pkt = Arrays.copyOfRange(buf, 0, dp.getLength());
            if (ok.test(pkt)) {
                peerPort = dp.getPort();
                return pkt;
            }
        }
    }

    void setReadTimeout(int ms) throws java.net.SocketException {
        socket.setSoTimeout(ms);
    }

    int peerPort() {
        return peerPort;
    }

    InetAddress peerIp() {
        return peerIp;
    }

    @Override
    public int read(byte[] buf) throws IOException {
        while (true) {
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            socket.receive(dp);
            int n = dp.getLength();
            // 同 IP（任意端口）或长度 >=8 的包才上送
            if (sameIp(dp.getAddress()) || n >= 8) {
                return n;
            }
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        socket.send(new DatagramPacket(data, data.length, peerIp, peerPort));
    }

    @Override
    public void close() {
        socket.close();
    }

    private boolean sameIp(InetAddress other) {
        return other != null && other.getHostAddress().equals(peerIp.getHostAddress());
    }
}
