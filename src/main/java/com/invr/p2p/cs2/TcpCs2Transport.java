package com.invr.p2p.cs2;

import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CS2 TCP 传输（1:1 对照 go2rtc tcpConn）。
 *
 * <p>外层帧：接收先读 8 字节头，BE16[0:2]=内层长度 n、[2]=0x68，再读满 n 字节；
 * 发送为 [BE16 len][0x68][6 字节 0][内层 payload]。
 */
final class TcpCs2Transport implements Cs2Transport {

    private static final Logger log = LoggerFactory.getLogger(TcpCs2Transport.class);
    /** DRW(0xD0) 数据帧：前 30 帧详细打印，之后每 200 帧打一次计数，避免媒体流刷屏。 */
    private final AtomicInteger drwCount = new AtomicInteger();
    private final AtomicInteger otherCount = new AtomicInteger();

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    TcpCs2Transport(InetAddress host, int port) throws IOException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(host, port), Cs2.TCP_DIAL_TIMEOUT_MS);
        this.socket.setTcpNoDelay(true);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        log.info("TCP 已连接 {} -> {}:{}", socket.getLocalPort(), host.getHostAddress(), port);
    }

    @Override
    public int read(byte[] buf) throws IOException {
        byte[] header = new byte[8];
        readFully(header, 0, 8);
        int n = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        int marker = header[2] & 0xFF;
        if (n > buf.length) {
            throw new IOException("cs2: tcp buffer too small: " + n);
        }
        readFully(buf, 0, n);
        int type = n >= 2 ? (buf[1] & 0xFF) : -1;
        if (type == Cs2.MSG_DRW) {
            int ch = n >= 6 ? (buf[5] & 0xFF) : -1;
            int c = drwCount.incrementAndGet();
            if (log.isTraceEnabled() && (c <= 30 || c % 200 == 0)) {
                log.trace("TCP << DRW#{} ch={} 内层{}字节: {}{}",
                        c, ch, n,
                        Hex.toHexString(buf, 0, Math.min(n, 44)),
                        n > 44 ? "…" : "");
            }
        } else if (otherCount.incrementAndGet() <= 4) {
            log.debug("TCP << 非DRW type=0x{} 内层{}字节", Integer.toHexString(type), n);
        }
        return n;
    }

    @Override
    public void write(byte[] data) throws IOException {
        byte[] frame = new byte[8 + data.length];
        frame[0] = (byte) ((data.length >>> 8) & 0xFF);
        frame[1] = (byte) (data.length & 0xFF);
        frame[2] = (byte) Cs2.MAGIC_TCP;
        // frame[3..7] 保持 0
        System.arraycopy(data, 0, frame, 8, data.length);
        out.write(frame);
        out.flush();
        if (log.isTraceEnabled()) {
            log.trace("TCP >> 外层{}字节(内层{}字节): {}{}",
                    frame.length, data.length,
                    Hex.toHexString(frame, 0, Math.min(frame.length, 56)),
                    frame.length > 56 ? "…" : "");
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private void readFully(byte[] b, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(b, off + read, len - read);
            if (n < 0) {
                throw new java.io.EOFException("cs2: tcp 连接已关闭");
            }
            read += n;
        }
    }
}
