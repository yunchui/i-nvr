package com.invr.p2p.cs2;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CS2 帧组包与 DataChannel 重组的字节级固化（对照 go2rtc marshalCmd / dataChannel）。
 */
class Cs2FramingTest {

    @Test
    void marshalCmdLayout() {
        // cmd=0x100，payload="abc"
        byte[] frame = Cs2Connection.marshalCmd(
                Cs2.CHANNEL_CMD, 0, 0x100, "abc".getBytes(StandardCharsets.UTF_8));

        // [F1 D0] BE16 size = 12+3 = 15
        assertEquals(0xF1, frame[0] & 0xFF);
        assertEquals(0xD0, frame[1] & 0xFF);
        assertEquals(0x00, frame[2] & 0xFF);
        assertEquals(0x0F, frame[3] & 0xFF);
        // [D1 channel=0][BE16 seq=0]
        assertEquals(0xD1, frame[4] & 0xFF);
        assertEquals(0x00, frame[5] & 0xFF);
        assertEquals(0x00, frame[6] & 0xFF);
        assertEquals(0x00, frame[7] & 0xFF);
        // BE32 payloadLen = 4+3 = 7
        assertArrayEquals(new byte[]{0, 0, 0, 7}, slice(frame, 8, 4));
        // BE32 cmd = 0x100 -> 00 00 01 00（go2rtc marshalCmd 写命令用大端；读回令才用小端）
        assertArrayEquals(new byte[]{0x00, 0x00, 0x01, 0x00}, slice(frame, 12, 4));
        // payload
        assertArrayEquals(new byte[]{'a', 'b', 'c'}, slice(frame, 16, 3));
        assertEquals(19, frame.length);
    }

    @Test
    void pushSingleMessageWithBe32Prefix() throws Exception {
        DataChannel ch = new DataChannel(0, 10);
        // 在线格式：BE32(5) + 5 字节消息体
        byte[] wire = {0, 0, 0, 5, 1, 2, 3, 4, 5};
        ch.push(wire);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, ch.pop());
    }

    @Test
    void pushMultipleMessagesInOnePacket() throws Exception {
        DataChannel ch = new DataChannel(0, 10);
        byte[] wire = {
                0, 0, 0, 2, 0x0A, 0x0B,
                0, 0, 0, 3, 0x0C, 0x0D, 0x0E};
        ch.push(wire);
        assertArrayEquals(new byte[]{0x0A, 0x0B}, ch.pop());
        assertArrayEquals(new byte[]{0x0C, 0x0D, 0x0E}, ch.pop());
    }

    @Test
    void pushFragmentedAcrossPackets() throws Exception {
        DataChannel ch = new DataChannel(0, 10);
        ch.push(new byte[]{0, 0, 0, 6, 0x11});        // 长度前缀(6) + 首字节
        ch.push(new byte[]{0x22, 0x33, 0x44, 0x55, 0x66}); // 补齐剩余 5 字节
        assertArrayEquals(new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}, ch.pop());
    }

    @Test
    void pushSeqReordersOutOfBandPackets() throws Exception {
        DataChannel ch = new DataChannel(250, 100);

        byte[] pkt0 = {0, 0, 0, 2, 'a', 'b'};
        byte[] pkt1 = {0, 0, 0, 2, 'c', 'd'};

        // 未来包 seq=1 先到：缓存，不交付
        assertEquals(0, ch.pushSeq(1, pkt1));
        // seq=0 到达：连续交付 seq0、seq1，共 2 条
        assertEquals(2, ch.pushSeq(0, pkt0));

        assertArrayEquals(new byte[]{'a', 'b'}, ch.pop());
        assertArrayEquals(new byte[]{'c', 'd'}, ch.pop());
    }

    @Test
    void pushSeqDropsPastPackets() throws Exception {
        DataChannel ch = new DataChannel(250, 100);
        byte[] pkt0 = {0, 0, 0, 1, 'x'};
        assertEquals(1, ch.pushSeq(0, pkt0));
        // 重复的旧 seq
        assertEquals(0, ch.pushSeq(0, pkt0));
        assertArrayEquals(new byte[]{'x'}, ch.pop());
    }

    @Test
    void pushSeqDisabledBufferReturnsMinusOne() {
        // 通道 0 关闭乱序缓存：未来包无法保存
        DataChannel ch = new DataChannel(0, 10);
        byte[] future = {0, 0, 0, 1, 'y'};
        try {
            assertEquals(-1, ch.pushSeq(5, future));
            // 关闭后无消息可交付
            ch.close();
            assertNull(ch.pop());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }
}
