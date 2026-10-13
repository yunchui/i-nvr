package com.invr.miss;

import java.util.ArrayList;
import java.util.List;

/**
 * Annex-B NAL 单元分析（探针/关键帧判定/切分）。
 *
 * <p>设备下发的视频负载为 Annex-B（起始码 00 00 00 01 / 00 00 01）。
 * M2 仅用于统计关键帧；StreamHub GOP 缓存与 RTP 封装复用 {@link #split}。
 */
public final class NalUnits {

    private NalUnits() {
    }

    /** 一个 NAL 单元（不含起始码的原始字节）及其类型。 */
    public static final class Nal {
        public final byte[] data;
        public final int type;

        public Nal(byte[] data, int type) {
            this.data = data;
            this.type = type;
        }
    }

    /** H.265 NAL 类型。 */
    public static int h265Type(byte nalHeader0) {
        return (nalHeader0 & 0x7E) >> 1;
    }

    /** H.264 NAL 类型。 */
    public static int h264Type(byte nalHeader0) {
        return nalHeader0 & 0x1F;
    }

    /**
     * 将一个 Annex-B 访问单元切分为若干 NAL（去掉起始码）。
     *
     * @param isH265 true=H.265（NAL 头 2 字节），false=H.264（NAL 头 1 字节）
     */
    public static List<Nal> split(boolean isH265, byte[] b) {
        List<Nal> out = new ArrayList<>();
        int n = b.length;
        // 收集每个起始码：[codeBeg, codeEnd)
        List<int[]> codes = new ArrayList<>();
        int i = 0;
        while (i + 2 < n) {
            if (b[i] == 0 && b[i + 1] == 0) {
                if (b[i + 2] == 1) {
                    codes.add(new int[]{i, i + 3});
                    i += 3;
                    continue;
                }
                if (i + 3 < n && b[i + 3] == 1) {
                    codes.add(new int[]{i, i + 4});
                    i += 4;
                    continue;
                }
            }
            i++;
        }
        for (int k = 0; k < codes.size(); k++) {
            int begin = codes.get(k)[1];
            int end = (k + 1 < codes.size()) ? codes.get(k + 1)[0] : n;
            if (end <= begin) {
                continue;
            }
            byte[] nal = new byte[end - begin];
            System.arraycopy(b, begin, nal, 0, nal.length);
            int type = isH265 ? h265Type(nal[0]) : h264Type(nal[0]);
            out.add(new Nal(nal, type));
        }
        return out;
    }

    /**
     * 判断一个视频包内是否含 IDR 关键帧。
     *
     * @param isH265 true=H.265，false=H.264
     */
    public static boolean containsIdr(boolean isH265, byte[] data) {
        int i = 0;
        int n = data.length;
        while (i < n) {
            int nalOffset;
            if (i + 3 < n && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                nalOffset = i + 3;
            } else if (i + 4 < n && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                nalOffset = i + 4;
            } else {
                i++;
                continue;
            }
            if (nalOffset < n) {
                int type = isH265 ? (data[nalOffset] & 0x7E) >> 1 : data[nalOffset] & 0x1F;
                if (isH265) {
                    if (type == 19 || type == 20) {
                        return true;
                    }
                } else if (type == 5) {
                    return true;
                }
            }
            i = nalOffset;
        }
        return false;
    }
}
