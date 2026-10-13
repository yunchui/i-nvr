package com.invr.codec;

import com.invr.miss.NalUnits;

import java.io.ByteArrayOutputStream;

/**
 * 视频解码器配置记录（hvcC / avcC）与 SPS 解析工具，全部为 Annex-B 输入、纯函数。
 *
 * <p>逻辑与 {@code record.Mp4SegmentRecorder} 内已真机验证的实现一致（MP4 录像在 H.265 上
 * 已验证可播），抽取为公共工具供 MP4 录像与 fMP4 HLS 复用：从参数集 NAL 构造配置记录，
 * 从 H.265 SPS 解析亮度分辨率。帧数据的 AVCC 化（剔除参数集、4 字节长度前缀）在各 muxer 侧
 * 基于 {@link NalUnits#split} 完成。</p>
 */
public final class VideoConfig {

    private VideoConfig() {
    }

    /**
     * 从含 VPS/SPS/PPS 的 H.265 Annex-B 访问单元构造 HEVCDecoderConfigurationRecord（hvcC）。
     * 不含参数集时返回 null。profile_tier_level 直接取自 SPS（max_sub_layers=0 常见情形）。
     */
    public static byte[] buildHevcConfigRecord(byte[] annexb) {
        byte[] vps = null, sps = null, pps = null;
        for (NalUnits.Nal n : NalUnits.split(true, annexb)) {
            byte[] nal = n.data;
            if (nal.length == 0) {
                continue;
            }
            if (n.type == 32 && vps == null) {
                vps = nal;
            } else if (n.type == 33 && sps == null) {
                sps = nal;
            } else if (n.type == 34 && pps == null) {
                pps = nal;
            }
        }
        if (vps == null || sps == null || pps == null || sps.length < 15) {
            return null;
        }
        int maxSub = (sps[2] >> 1) & 0x07;
        boolean nested = (sps[2] & 0x01) != 0;
        byte[] ptl = new byte[12];
        if (maxSub == 0) {
            // NAL header(2) + sps 标志字节(1) 后即 12 字节 profile_tier_level
            System.arraycopy(sps, 3, ptl, 0, 12);
        } else {
            // 罕见多层情形，使用保守 Main / Level 兜底
            ptl[0] = 0x01;
            ptl[11] = 0x78;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x01);
        o.write(ptl, 0, 12);
        o.write(0xF0);
        o.write(0x00);                 // min_spatial_segmentation_idc = 0
        o.write(0xFC);                 // parallelismType = 0
        o.write(0xFD);                 // chromaFormat = 1 (4:2:0)
        o.write(0xF8);                 // bitDepthLumaMinus8 = 0
        o.write(0xF8);                 // bitDepthChromaMinus8 = 0
        o.write(0x00);
        o.write(0x00);                 // avgFrameRate = 0
        o.write(((maxSub + 1) << 3) | (nested ? 0x04 : 0x00) | 0x03); // lengthSizeMinusOne = 3
        o.write(0x03);                 // numOfArrays = VPS/SPS/PPS
        writeHevcArray(o, 32, vps);
        writeHevcArray(o, 33, sps);
        writeHevcArray(o, 34, pps);
        return o.toByteArray();
    }

    private static void writeHevcArray(ByteArrayOutputStream o, int nalType, byte[] nal) {
        o.write(0x80 | (nalType & 0x3F)); // array_completeness = 1
        o.write(0x00);
        o.write(0x01);                    // numNalus = 1
        o.write((nal.length >> 8) & 0xFF);
        o.write(nal.length & 0xFF);
        o.write(nal, 0, nal.length);
    }

    /** 从含 SPS/PPS 的 H.264 Annex-B 访问单元构造 AVCDecoderConfigurationRecord（avcC）。 */
    public static byte[] buildAvcConfigRecord(byte[] annexb) {
        byte[] sps = null, pps = null;
        for (NalUnits.Nal n : NalUnits.split(false, annexb)) {
            byte[] nal = n.data;
            if (nal.length == 0) {
                continue;
            }
            if (n.type == 7 && sps == null) {
                sps = nal;
            } else if (n.type == 8 && pps == null) {
                pps = nal;
            }
        }
        if (sps == null || pps == null || sps.length < 4) {
            return null;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x01);
        o.write(sps[1] & 0xFF);          // profile_idc
        o.write(sps[2] & 0xFF);          // constraint flags
        o.write(sps[3] & 0xFF);          // level_idc
        o.write(0xFF);                   // reserved(6) + lengthSizeMinusOne=3
        o.write(0xE1);                   // reserved(3) + numOfSPS=1
        o.write((sps.length >> 8) & 0xFF);
        o.write(sps.length & 0xFF);
        o.write(sps, 0, sps.length);
        o.write(0x01);                   // numOfPPS=1
        o.write((pps.length >> 8) & 0xFF);
        o.write(pps.length & 0xFF);
        o.write(pps, 0, pps.length);
        return o.toByteArray();
    }

    /** 从含 SPS 的 H.265 Annex-B 访问单元解析亮度分辨率 [width,height]，失败返回 null。 */
    public static int[] parseHevcDimensionsFromAu(byte[] annexb) {
        for (NalUnits.Nal n : NalUnits.split(true, annexb)) {
            if (n.type == 33) {
                return parseHevcDimensions(n.data);
            }
        }
        return null;
    }

    /** 解析 H.265 SPS NAL（含 2 字节 NAL 头）得到图像宽高；仅实现 max_sub_layers=0 的常见情形。 */
    public static int[] parseHevcDimensions(byte[] spsNal) {
        try {
            byte[] rb = removeEmulationPrevention(spsNal);
            BitReader r = new BitReader(rb, 16); // 跳过 2 字节 NAL header
            r.bits(4);                        // sps_video_parameter_set_id
            int maxSub = r.bits(3);           // sps_max_sub_layers_minus1
            r.bits(1);                        // sps_temporal_id_nesting_flag
            if (maxSub != 0) {
                return null;
            }
            r.bits(8 + 32 + 48 + 8);          // profile_tier_level（96 bits）
            r.ue();                           // sps_seq_parameter_set_id
            int chroma = r.ue();              // chroma_format_idc
            if (chroma == 3) {
                r.bits(1);                    // separate_colour_plane_flag
            }
            int w = r.ue();                   // pic_width_in_luma_samples
            int h = r.ue();                   // pic_height_in_luma_samples
            int crop = r.bits(1);             // conformance_window_flag
            int cl = 0, cr = 0, ct = 0, cb = 0;
            if (crop == 1) {
                cl = r.ue();
                cr = r.ue();
                ct = r.ue();
                cb = r.ue();
            }
            int cw = (chroma == 1 || chroma == 2) ? 2 : 1;
            int ch = (chroma == 1) ? 2 : 1;
            w -= (cl + cr) * cw;
            h -= (ct + cb) * ch;
            if (w <= 0 || h <= 0) {
                return null;
            }
            return new int[]{w, h};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 去除 RBSP 中的 emulation prevention byte（00 00 03 → 00 00）。 */
    private static byte[] removeEmulationPrevention(byte[] nal) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(nal.length);
        int i = 0;
        int n = nal.length;
        while (i < n) {
            if (i + 2 < n && nal[i] == 0 && nal[i + 1] == 0 && nal[i + 2] == 3) {
                o.write(0);
                o.write(0);
                i += 3;
            } else {
                o.write(nal[i] & 0xFF);
                i++;
            }
        }
        return o.toByteArray();
    }

    /** MSB 优先位读取器，支持 H.264/H.265 exp-Golomb（ue）。 */
    static final class BitReader {
        private final byte[] data;
        private int pos;

        BitReader(byte[] data, int startBit) {
            this.data = data;
            this.pos = startBit;
        }

        int bit() {
            int v = (data[pos >> 3] >> (7 - (pos & 7))) & 0x01;
            pos++;
            return v;
        }

        int bits(int n) {
            int v = 0;
            for (int k = 0; k < n; k++) {
                v = (v << 1) | bit();
            }
            return v;
        }

        int ue() {
            int zeros = 0;
            while (pos < data.length * 8 && bit() == 0) {
                zeros++;
                if (zeros > 32) {
                    throw new IllegalStateException("ue 前导零过长");
                }
            }
            return (1 << zeros) - 1 + bits(zeros);
        }
    }
}
