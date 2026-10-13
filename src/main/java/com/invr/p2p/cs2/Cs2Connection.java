package com.invr.p2p.cs2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

/**
 * CS2 连接：worker 收包线程 + 通道 0（命令）/通道 2（媒体）重组与对外读写。
 *
 * <p>1:1 对照 go2rtc pkg/xiaomi/miss/cs2/conn.go 的 Conn。
 */
public final class Cs2Connection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Cs2Connection.class);

    private final Cs2Transport transport;
    private final boolean tcp;
    private final DataChannel[] channels = new DataChannel[4];

    private final Object cmdLock = new Object();
    private final Object cmdAckLock = new Object();
    private int seqCh0;
    private int seqCh3;
    private volatile boolean cmdAcked;

    private Thread worker;
    private volatile Throwable error;
    private volatile boolean closed;

    public Cs2Connection(Cs2Transport transport, boolean tcp) {
        this.transport = transport;
        this.tcp = tcp;
        // 通道 0：无乱序缓存、pop 队列 10；通道 2：乱序缓存 250、pop 队列 100
        this.channels[Cs2.CHANNEL_CMD] = new DataChannel(0, 10);
        this.channels[Cs2.CHANNEL_MEDIA] = new DataChannel(250, 100);
    }

    void start() {
        worker = new Thread(this::run, "cs2-worker");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean isTcp() {
        return tcp;
    }

    public String protocol() {
        return tcp ? "cs2+tcp" : "cs2+udp";
    }

    // ------------------------------------------------------------------
    // worker 收包
    // ------------------------------------------------------------------

    private void run() {
        byte[] buf = new byte[Cs2.READ_BUF_SIZE];
        long keepaliveDeadline = 0L;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int n = transport.read(buf);
                int type = buf[1] & 0xFF;
                switch (type) {
                    case Cs2.MSG_DRW -> {
                        int ch = buf[5] & 0xFF;
                        DataChannel channel = ch < channels.length ? channels[ch] : null;
                        if (channel == null) {
                            log.warn("cs2: 未知通道 {}", ch);
                            break;
                        }
                        byte[] payload = Arrays.copyOfRange(buf, 8, n);
                        if (tcp) {
                            // 官方米家 App TCP 下约每秒发一次 PING 保活
                            long now = System.currentTimeMillis();
                            if (now > keepaliveDeadline) {
                                transport.write(new byte[]{(byte) Cs2.MAGIC, (byte) Cs2.MSG_PING, 0, 0});
                                keepaliveDeadline = now + Cs2.TCP_PING_INTERVAL_MS;
                            }
                            channel.push(payload);
                        } else {
                            int seqHi = buf[6] & 0xFF;
                            int seqLo = buf[7] & 0xFF;
                            int seq = (seqHi << 8) | seqLo;
                            int pushed = channel.pushSeq(seq, payload);
                            if (pushed >= 0) {
                                byte[] ack = {(byte) Cs2.MAGIC, (byte) Cs2.MSG_DRW_ACK, 0, 6,
                                        (byte) Cs2.MAGIC_DRW, (byte) ch, 0, 1,
                                        (byte) seqHi, (byte) seqLo};
                                transport.write(ack);
                            }
                        }
                    }
                    case Cs2.MSG_PING -> transport.write(
                            new byte[]{(byte) Cs2.MAGIC, (byte) Cs2.MSG_PONG, 0, 0});
                    case Cs2.MSG_PONG, Cs2.MSG_P2P_RDY_UDP, Cs2.MSG_P2P_RDY_TCP,
                            Cs2.MSG_CLOSE, Cs2.MSG_CLOSE_ACK -> {
                        // 忽略
                    }
                    case Cs2.MSG_DRW_ACK -> {
                        synchronized (cmdAckLock) {
                            cmdAcked = true;
                            cmdAckLock.notifyAll();
                        }
                    }
                    default -> log.warn("cs2: 未知消息类型 0x{} 长度 {}", Integer.toHexString(type), n);
                }
            }
        } catch (Throwable t) {
            if (closed) {
                // 主动关闭导致的读中断（Socket closed / EOF），属正常退出
                log.debug("cs2 worker 随连接主动关闭退出：{}", t.toString());
            } else {
                // 真实断流：记录为错误源以唤醒 readPacket，交由 StreamHub 重连（不打印堆栈噪音）
                log.warn("cs2 worker 收包线程退出（连接中断）：{}", t.toString());
                error = t;
            }
        } finally {
            channels[Cs2.CHANNEL_CMD].close();
            channels[Cs2.CHANNEL_MEDIA].close();
        }
    }

    // ------------------------------------------------------------------
    // 命令通道（0）
    // ------------------------------------------------------------------

    /**
     * 读取一条命令响应：LE32 cmd + body（无限等待，连接关闭即抛错）。
     */
    public byte[] readCommandRaw() throws IOException {
        return readCommandRaw(0);
    }

    /**
     * 读取一条命令响应。
     *
     * @param timeoutMs 大于 0 时的等待超时；0 表示无限等待
     */
    public byte[] readCommandRaw(long timeoutMs) throws IOException {
        try {
            byte[] data = timeoutMs > 0
                    ? channels[Cs2.CHANNEL_CMD].pop(timeoutMs)
                    : channels[Cs2.CHANNEL_CMD].pop();
            if (data == null) {
                if (error != null) {
                    throw closedOrError();
                }
                if (timeoutMs > 0) {
                    throw new IOException("cs2: 等待命令响应超时（" + timeoutMs + "ms 无数据）");
                }
                throw closedOrError();
            }
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw closedOrError();
        }
    }

    /**
     * 发送命令：UDP 下每秒重传、最多 5 次，收到 DRW_ACK 即成功；TCP 直接发送。
     */
    public void writeCommand(int cmd, byte[] payload) throws IOException {
        synchronized (cmdLock) {
            byte[] req = marshalCmd(Cs2.CHANNEL_CMD, seqCh0 & 0xFFFF, cmd, payload);
            seqCh0++;
            if (tcp) {
                transport.write(req);
                return;
            }
            synchronized (cmdAckLock) {
                cmdAcked = false;
            }
            int remaining = Cs2.CMD_MAX_SENDS;
            while (true) {
                transport.write(req);
                if (awaitAck()) {
                    return;
                }
                remaining--;
                if (remaining <= 0) {
                    throw new IOException("cs2: 命令 " + cmd + " 发送后未收到 ACK（已重发 "
                            + Cs2.CMD_MAX_SENDS + " 次）");
                }
            }
        }
    }

    private boolean awaitAck() throws IOException {
        synchronized (cmdAckLock) {
            if (cmdAcked) {
                return true;
            }
            try {
                cmdAckLock.wait(Cs2.RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("cs2: 等待命令 ACK 被中断", e);
            }
            return cmdAcked;
        }
    }

    // ------------------------------------------------------------------
    // 媒体通道（2 下行 / 3 上行）
    // ------------------------------------------------------------------

    /**
     * 读取一条媒体消息：返回 [32B 头][密文 payload] 的完整消息体。
     * 取流阶段 {@value Cs2#MEDIA_READ_TIMEOUT_MS}ms 无包即判断流并抛错。
     */
    public byte[] readMediaMessage() throws IOException {
        try {
            byte[] data = channels[Cs2.CHANNEL_MEDIA].pop(Cs2.MEDIA_READ_TIMEOUT_MS);
            if (data == null) {
                if (error != null) {
                    throw closedOrError();
                }
                throw new IOException("cs2: " + Cs2.MEDIA_READ_TIMEOUT_MS
                        + "ms 未收到媒体包（设备可能被其他客户端占用或已断流）");
            }
            if (data.length < Cs2.HDR_SIZE) {
                throw new IOException("cs2: 媒体消息短于 32 字节头：" + data.length);
            }
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw closedOrError();
        }
    }

    /**
     * 音频上行封包（一期不使用）：通道 3，[32B 头][密文]。
     */
    public void writeAudioPacket(byte[] header, byte[] encryptedPayload) throws IOException {
        synchronized (cmdLock) {
            int n = Cs2.HDR_SIZE + encryptedPayload.length;
            byte[] req = new byte[12 + n];
            req[0] = (byte) Cs2.MAGIC;
            req[1] = (byte) Cs2.MSG_DRW;
            req[2] = (byte) ((n + 8) >>> 8);
            req[3] = (byte) ((n + 8) & 0xFF);
            req[4] = (byte) Cs2.MAGIC_DRW;
            req[5] = (byte) Cs2.CHANNEL_AUDIO;
            req[6] = (byte) ((seqCh3 >>> 8) & 0xFF);
            req[7] = (byte) (seqCh3 & 0xFF);
            seqCh3++;
            req[8] = (byte) ((n >>> 24) & 0xFF);
            req[9] = (byte) ((n >>> 16) & 0xFF);
            req[10] = (byte) ((n >>> 8) & 0xFF);
            req[11] = (byte) (n & 0xFF);
            System.arraycopy(header, 0, req, 12, Cs2.HDR_SIZE);
            System.arraycopy(encryptedPayload, 0, req, 12 + Cs2.HDR_SIZE, encryptedPayload.length);
            transport.write(req);
        }
    }

    // ------------------------------------------------------------------

    private IOException closedOrError() {
        Throwable t = error;
        if (t instanceof IOException ioe) {
            return ioe;
        }
        if (t != null) {
            return new IOException("cs2: 连接异常: " + t, t);
        }
        return new EOFException("cs2: 连接已关闭");
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (worker != null) {
            worker.interrupt();
        }
        transport.close();
        channels[Cs2.CHANNEL_CMD].close();
        channels[Cs2.CHANNEL_MEDIA].close();
    }

    /**
     * 组 DRW 命令帧：
     * <pre>
     * [F1 D0 BE16 size][D1 ch BE16 seq][BE32 payloadLen][LE32 cmd][payload]
     * size=12+len(payload)，payloadLen=4+len(payload)
     * </pre>
     */
    static byte[] marshalCmd(int channel, int seq, int cmd, byte[] payload) {
        int size = payload == null ? 0 : payload.length;
        byte[] req = new byte[16 + size];
        req[0] = (byte) Cs2.MAGIC;
        req[1] = (byte) Cs2.MSG_DRW;
        req[2] = (byte) ((12 + size) >>> 8);
        req[3] = (byte) ((12 + size) & 0xFF);
        req[4] = (byte) Cs2.MAGIC_DRW;
        req[5] = (byte) channel;
        req[6] = (byte) ((seq >>> 8) & 0xFF);
        req[7] = (byte) (seq & 0xFF);
        req[8] = (byte) ((4 + size) >>> 24);
        req[9] = (byte) ((4 + size) >>> 16);
        req[10] = (byte) ((4 + size) >>> 8);
        req[11] = (byte) ((4 + size) & 0xFF);
        // payload 命令字段：大端（go2rtc marshalCmd 用 binary.BigEndian.PutUint32）。
        // 注意读方向 ReadCommand 解析设备回令用小端，收发不对称，以 go2rtc 源码为准。
        req[12] = (byte) ((cmd >>> 24) & 0xFF);
        req[13] = (byte) ((cmd >>> 16) & 0xFF);
        req[14] = (byte) ((cmd >>> 8) & 0xFF);
        req[15] = (byte) (cmd & 0xFF);
        if (size > 0) {
            System.arraycopy(payload, 0, req, 16, size);
        }
        return req;
    }
}
