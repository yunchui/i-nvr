package com.invr.rtsp;

import com.invr.miss.NalUnits;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H.265 RTP 打包（RFC 7798）关键行为回归：参数集走 AP(48)、大 NAL 走 FU(49) 且 S/E 正确、
 * 非 base-layer 与残片 NAL 被剥离、FU 分片可无损重组。
 */
class RtpPackerTest {

    private static byte[] h265Nal(int type, int payloadLen) {
        byte[] nal = new byte[2 + payloadLen];
        nal[0] = (byte) ((type & 0x3F) << 1); // F=0, type, layer 高位=0
        nal[1] = 0x01;                        // layer 低5位=0, temporal_id_plus1=1
        for (int i = 2; i < nal.length; i++) {
            nal[i] = (byte) ((i * 7 + type) & 0xFF);
        }
        return nal;
    }

    private static int h265Type(byte[] nal) {
        return (nal[0] >> 1) & 0x3F;
    }

    @Test
    void packsConfigIntoApAndLargeIdrIntoFu() {
        List<NalUnits.Nal> nals = new ArrayList<>();
        nals.add(new NalUnits.Nal(h265Nal(35, 5), 35));   // AUD 应被丢弃
        nals.add(new NalUnits.Nal(h265Nal(32, 10), 32));  // VPS
        nals.add(new NalUnits.Nal(h265Nal(33, 30), 33));  // SPS
        nals.add(new NalUnits.Nal(h265Nal(34, 12), 34));  // PPS
        byte[] idr = h265Nal(19, 5000);                   // IDR，远超 PAYLOAD_MAX，必须 FU
        nals.add(new NalUnits.Nal(idr, 19));

        List<RtpPacker.Fragment> frags = RtpPacker.packH265(nals);
        assertTrue(frags.size() >= 2, "至少应有 AP + 多个 FU");

        // 第一个片段：AP(48)
        byte[] ap = frags.get(0).payload;
        assertEquals(48, h265Type(ap), "首个片段应为 AP(type=48)");
        List<byte[]> apNals = new ArrayList<>();
        int pos = 2; // 跳过 2 字节 AP indicator
        while (pos + 2 <= ap.length) {
            int len = ((ap[pos] & 0xFF) << 8) | (ap[pos + 1] & 0xFF);
            pos += 2;
            byte[] nal = new byte[len];
            System.arraycopy(ap, pos, nal, 0, len);
            pos += len;
            apNals.add(nal);
        }
        assertEquals(3, apNals.size(), "AP 内应聚合 VPS/SPS/PPS 三个参数集（AUD 已丢弃）");
        assertEquals(32, h265Type(apNals.get(0)));
        assertEquals(33, h265Type(apNals.get(1)));
        assertEquals(34, h265Type(apNals.get(2)));

        // 其余片段：FU(49)
        List<byte[]> fu = new ArrayList<>();
        for (int i = 1; i < frags.size(); i++) {
            byte[] f = frags.get(i).payload;
            assertEquals(49, h265Type(f), "IDR 分片应为 FU(type=49)");
            fu.add(f);
        }
        assertTrue(fu.size() > 1, "5000 字节 IDR 应被切成多个 FU");

        // 首片 S=1 E=0；中间 S=0 E=0；末片 S=0 E=1；FU type=19
        assertEquals(0x80 | 19, fu.get(0)[2] & 0xFF, "首片 FU header 应 S=1 且类型19");
        for (int i = 1; i < fu.size() - 1; i++) {
            assertEquals(19, fu.get(i)[2] & 0x3F, "中间片类型19");
            assertEquals(0, fu.get(i)[2] & 0xC0, "中间片 S/E 均为 0");
        }
        assertEquals(0x40 | 19, fu.get(fu.size() - 1)[2] & 0xFF, "末片 FU header 应 E=1 且类型19");

        // 无损重组：去掉每片 2B indicator + 1B FU header，拼接后等于 IDR 去掉 2B NAL 头
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] f : fu) {
            body.write(f, 3, f.length - 3);
        }
        byte[] reassembled = body.toByteArray();
        assertEquals(idr.length - 2, reassembled.length, "FU 重组长度应等于 IDR RBSP");
        for (int i = 0; i < reassembled.length; i++) {
            assertEquals(idr[i + 2], reassembled[i], "FU 重组字节应无损，位置 " + i);
        }
    }

    @Test
    void stripsNonBaseLayerAndMalformedNals() {
        List<NalUnits.Nal> nals = new ArrayList<>();
        nals.add(new NalUnits.Nal(h265Nal(33, 20), 33));  // base SPS
        nals.add(new NalUnits.Nal(h265Nal(34, 12), 34));  // base PPS

        // 伪造一个 nuh_layer_id=1 的 IDR（应被剥离）
        byte[] layeredIdr = h265Nal(19, 100);
        layeredIdr[1] = (byte) (1 << 3); // layer 低 5 位置 1
        nals.add(new NalUnits.Nal(layeredIdr, 19));
        // 1 字节残片（应被丢弃）
        nals.add(new NalUnits.Nal(new byte[]{0x00}, 0));

        List<RtpPacker.Fragment> frags = RtpPacker.packH265(nals);
        // 只剩 AP（SPS/PPS），分层 IDR 与残片均不应产生任何片段
        assertEquals(1, frags.size(), "分层 NAL 与残片被剥离后应只剩参数集 AP");
        byte[] ap = frags.get(0).payload;
        int pos = 2;
        int count = 0;
        while (pos + 2 <= ap.length) {
            int len = ((ap[pos] & 0xFF) << 8) | (ap[pos + 1] & 0xFF);
            pos += 2 + len;
            count++;
        }
        assertEquals(2, count, "AP 内仅 base 层 SPS/PPS");
    }

    @Test
    void smallVclNalSentAsSinglePacket() {
        List<NalUnits.Nal> nals = new ArrayList<>();
        nals.add(new NalUnits.Nal(h265Nal(33, 20), 33));
        nals.add(new NalUnits.Nal(h265Nal(34, 12), 34));
        byte[] p = h265Nal(1, 100); // 小 P/TRAIL NAL，单包直发
        nals.add(new NalUnits.Nal(p, 1));

        List<RtpPacker.Fragment> frags = RtpPacker.packH265(nals);
        // AP(SPS/PPS) + 单 NAL(P)
        assertEquals(2, frags.size());
        assertEquals(1, h265Type(frags.get(1).payload), "小 VCL NAL 应作为单 NAL 包直发(type=1)");
        assertEquals(p.length, frags.get(1).payload.length);
    }

    @Test
    void encoderSetsHeaderMarkerSeqAndTimestamp() {
        RtpEncoder enc = new RtpEncoder(0x01020304, 100);
        byte[] payload = new byte[]{1, 2, 3, 4};
        byte[] pkt = enc.build(96, true, 0x12345678L, payload);
        assertEquals(0x80, pkt[0] & 0xFF, "V=2");
        assertEquals(0x80 | 96, pkt[1] & 0xFF, "marker=1, pt=96");
        assertEquals(100, ((pkt[2] & 0xFF) << 8) | (pkt[3] & 0xFF), "起始序号 100");
        assertEquals(0x12, pkt[4] & 0xFF);
        assertEquals(0x78, pkt[7] & 0xFF);
        assertEquals(0x01, pkt[8] & 0xFF);
        assertEquals(0x04, pkt[11] & 0xFF);
        byte[] pkt2 = enc.build(96, false, 0, payload);
        assertEquals(101, ((pkt2[2] & 0xFF) << 8) | (pkt2[3] & 0xFF), "序号自增");
        assertFalse((pkt2[1] & 0x80) != 0, "第二包 marker=0");
    }
}
