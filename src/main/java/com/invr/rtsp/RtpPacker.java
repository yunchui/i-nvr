package com.invr.rtsp;

import com.invr.miss.NalUnits;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * RTP 打包（纯函数，无网络依赖，便于单测）。
 *
 * <ul>
 *   <li>视频 H.265：RFC 7798（AP=48 聚合参数集，FU=49 分片大 NAL，单 NAL 直发）；</li>
 *   <li>视频 H.264：RFC 6184（STAP-A=24，FU-A=28）；</li>
 *   <li>音频 Opus：RFC 7587，每个 Opus 帧一个 RTP 包。</li>
 * </ul>
 *
 * <p>输出为"RTP payload 片段"（不含 12 字节 RTP 固定头），由 {@link RtpEncoder} 加头；
 * 同一访问单元的所有片段共享同一 RTP 时间戳，仅最后一个片段 marker=1。</p>
 */
public final class RtpPacker {

    private RtpPacker() {
    }

    /** 单个 RTP 包的负载（不含 RTP 固定头）。 */
    public static final class Fragment {
        public final byte[] payload;

        public Fragment(byte[] payload) {
            this.payload = payload;
        }
    }

    /** 目标 RTP 报文总长（含 12B 头）上限；RTP over TCP 无路径 MTU 硬限制，取保守值兼容播放器。 */
    public static final int RTP_MAX_PACKET = 1400;
    public static final int RTP_HEADER_LEN = 12;
    public static final int PAYLOAD_MAX = RTP_MAX_PACKET - RTP_HEADER_LEN;

    // ---- H.265 (RFC 7798) ----

    private static final int H265_AP = 48;
    private static final int H265_FU = 49;

    public static List<Fragment> packH265(List<NalUnits.Nal> nals) {
        List<Fragment> out = new ArrayList<>();

        // 参数集/SEI 等非 VCL 小 NAL 聚合进 AP；VCL 大 NAL 走 FU、小 VCL 直发
        List<NalUnits.Nal> configGroup = new ArrayList<>();
        List<NalUnits.Nal> vcl = new ArrayList<>();
        for (NalUnits.Nal nal : nals) {
            if (nal.data == null || nal.data.length < 2) {
                continue; // H.265 NAL 头占 2 字节，1 字节残留/填充直接丢弃
            }
            // 单层下行：只转发 nuh_layer_id==0 的 base layer；
            // 设备偶发的多层 NAL 与拼接边界误切出的高 layer 垃圾单元一律剥离（否则播放器报 Multi-layer）
            int layerId = ((nal.data[0] & 0x01) << 5) | ((nal.data[1] & 0xFF) >>> 3);
            if (layerId != 0) {
                continue;
            }
            int t = nal.type;
            if (t == 35) {
                continue; // AUD 无需下发
            }
            if (isH265Config(t)) {
                configGroup.add(nal);
            } else if (t <= 31) {
                vcl.add(nal); // VCL（TRAIL/CRA/IDR 等）
            } else {
                configGroup.add(nal); // 其余非 VCL（SEI 等）随参数集聚合
            }
        }

        if (!configGroup.isEmpty()) {
            appendAp265(configGroup, out);
        }
        for (NalUnits.Nal nal : vcl) {
            if (nal.data.length <= PAYLOAD_MAX) {
                out.add(new Fragment(nal.data));
            } else {
                appendFu265(nal, out);
            }
        }
        return out;
    }

    private static boolean isH265Config(int t) {
        return t == 32 || t == 33 || t == 34 // VPS/SPS/PPS
                || t == 39 || t == 40;      // SEI prefix/suffix
    }

    private static void appendAp265(List<NalUnits.Nal> group, List<Fragment> out) {
        // AP 可能因参数集过多超长，按 PAYLOAD_MAX 拆成多个 AP
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] ind = apIndicator265(group.get(0).data);
        writeBytes(o, ind, 0, 2);
        for (NalUnits.Nal nal : group) {
            int overhead = 2 + 2 + nal.data.length; // indicator 已写，这里仅估算 2B 长度前缀
            if (o.size() + overhead > PAYLOAD_MAX && o.size() > 2) {
                out.add(new Fragment(o.toByteArray()));
                o.reset();
                writeBytes(o, apIndicator265(nal.data), 0, 2);
            }
            writeBe16(o, nal.data.length);
            writeBytes(o, nal.data, 0, nal.data.length);
        }
        if (o.size() > 2) {
            out.add(new Fragment(o.toByteArray()));
        }
    }

    private static byte[] apIndicator265(byte[] origHeader) {
        byte[] ind = new byte[2];
        // 保留 F(bit7) 与 layerId 高位(bit0)，把 type 字段替换为 48
        ind[0] = (byte) ((origHeader[0] & 0x81) | (H265_AP << 1));
        ind[1] = origHeader[1];
        return ind;
    }

    private static void appendFu265(NalUnits.Nal nal, List<Fragment> out) {
        int type = nal.type;
        byte[] ind = new byte[2];
        ind[0] = (byte) ((nal.data[0] & 0x81) | (H265_FU << 1));
        ind[1] = nal.data[1];
        int bodyOff = 2; // 跳过 2 字节 NAL 头
        int chunk = PAYLOAD_MAX - 3; // indicator(2) + FU header(1)
        int pos = bodyOff;
        boolean first = true;
        while (pos < nal.data.length) {
            int len = Math.min(chunk, nal.data.length - pos);
            boolean last = pos + len == nal.data.length;
            byte fu = (byte) ((first ? 0x80 : 0) | (last ? 0x40 : 0) | (type & 0x3F));
            byte[] f = new byte[3 + len];
            f[0] = ind[0];
            f[1] = ind[1];
            f[2] = fu;
            System.arraycopy(nal.data, pos, f, 3, len);
            out.add(new Fragment(f));
            pos += len;
            first = false;
        }
    }

    // ---- H.264 (RFC 6184) ----

    private static final int H264_STAP_A = 24;
    private static final int H264_FU_A = 28;

    public static List<Fragment> packH264(List<NalUnits.Nal> nals) {
        List<Fragment> out = new ArrayList<>();
        List<NalUnits.Nal> configGroup = new ArrayList<>();
        List<NalUnits.Nal> vcl = new ArrayList<>();
        int maxNri = 0;
        for (NalUnits.Nal nal : nals) {
            if (nal.data == null || nal.data.length < 1) {
                continue;
            }
            int t = nal.type;
            maxNri = Math.max(maxNri, nal.data[0] & 0x60);
            if (t == 9) {
                continue; // AUD
            }
            if (t == 7 || t == 8 || t == 6) {
                configGroup.add(nal); // SPS/PPS/SEI
            } else if (t == 1 || t == 5 || (t >= 2 && t <= 4)) {
                vcl.add(nal);
            } else {
                configGroup.add(nal);
            }
        }

        if (!configGroup.isEmpty()) {
            appendStapA(configGroup, maxNri, out);
        }
        for (NalUnits.Nal nal : vcl) {
            if (nal.data.length <= PAYLOAD_MAX) {
                out.add(new Fragment(nal.data));
            } else {
                appendFuA(nal, out);
            }
        }
        return out;
    }

    private static void appendStapA(List<NalUnits.Nal> group, int nri, List<Fragment> out) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((byte) ((H264_STAP_A & 0x1F) | nri));
        for (NalUnits.Nal nal : group) {
            if (o.size() + 2 + nal.data.length > PAYLOAD_MAX && o.size() > 1) {
                out.add(new Fragment(o.toByteArray()));
                o.reset();
                o.write((byte) ((H264_STAP_A & 0x1F) | (nal.data[0] & 0x60)));
            }
            writeBe16(o, nal.data.length);
            writeBytes(o, nal.data, 0, nal.data.length);
        }
        if (o.size() > 1) {
            out.add(new Fragment(o.toByteArray()));
        }
    }

    private static void appendFuA(NalUnits.Nal nal, List<Fragment> out) {
        int type = nal.type;
        int nri = nal.data[0] & 0x60;
        byte indicator = (byte) ((H264_FU_A & 0x1F) | nri);
        int chunk = PAYLOAD_MAX - 2; // indicator(1) + FU header(1)
        int pos = 1; // 跳过 1 字节 NAL 头
        boolean first = true;
        while (pos < nal.data.length) {
            int len = Math.min(chunk, nal.data.length - pos);
            boolean last = pos + len == nal.data.length;
            byte fu = (byte) ((first ? 0x80 : 0) | (last ? 0x40 : 0) | (type & 0x1F));
            byte[] f = new byte[2 + len];
            f[0] = indicator;
            f[1] = fu;
            System.arraycopy(nal.data, pos, f, 2, len);
            out.add(new Fragment(f));
            pos += len;
            first = false;
        }
    }

    // ---- 工具 ----

    static void writeBe16(ByteArrayOutputStream o, int v) {
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    static void writeBytes(ByteArrayOutputStream o, byte[] b, int off, int len) {
        o.write(b, off, len);
    }
}
