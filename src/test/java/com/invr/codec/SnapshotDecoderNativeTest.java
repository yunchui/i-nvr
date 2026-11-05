package com.invr.codec;

import com.invr.miss.MissPacket;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacpp.BytePointer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MJPEG;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame;
import static org.bytedeco.ffmpeg.global.avutil.AV_PICTURE_TYPE_I;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link SnapshotDecoder} 的 FFmpeg 原生链路测试：用本机 FFmpeg 编码器现场合成 H.264
 * Annex-B 关键帧（带内 SPS/PPS），喂给截帧解码器后应得到带 SOI/EOI 的完整 JPEG。
 *
 * <p>依赖平台原生库（pom 已带 windows-x86_64 / linux-x86_64 classifier）；在缺少原生库
 * 或 H.264 编码器的环境中整条测试自动跳过，而非失败，避免纯逻辑构建环境被原生依赖卡死。</p>
 */
class SnapshotDecoderNativeTest {

    private static final int WIDTH = 160;
    private static final int HEIGHT = 96;

    @BeforeAll
    static void requireNativeFfmpeg() {
        boolean natives;
        try {
            natives = avcodec_version_probe();
        } catch (LinkageError | Exception e) {
            natives = false;
        }
        assumeTrue(natives, "本机未加载 FFmpeg 原生库，跳过截帧原生测试");
    }

    private static boolean avcodec_version_probe() {
        return org.bytedeco.ffmpeg.global.avcodec.avcodec_version() > 0
                && avcodec_find_decoder(AV_CODEC_ID_HEVC) != null
                && avcodec_find_encoder(AV_CODEC_ID_MJPEG) != null;
    }

    @Test
    void decodesSyntheticH264KeyframeToJpeg() {
        List<byte[]> annexBPackets = encodeH264AnnexB(WIDTH, HEIGHT, 3);
        assumeTrue(!annexBPackets.isEmpty(), "本机无可用 H.264 编码器，跳过合成解码测试");

        SnapshotDecoder decoder = new SnapshotDecoder();
        try {
            byte[] jpeg = null;
            int seq = 0;
            for (byte[] payload : annexBPackets) {
                MissPacket pkt = new MissPacket(4, seq++, 0, seq * 40L, payload);
                jpeg = decoder.accept(pkt);
                if (jpeg != null) {
                    break;
                }
            }
            assertNotNull(jpeg, "喂入合成 H.264 Annex-B 关键帧后应解出首帧 JPEG");
            // 160x96 平滑渐变图经 MJPEG 默认质量压缩后体积本就较小，这里只做合理性下界校验
            assertTrue(jpeg.length > 200, "JPEG 体积异常过小: " + jpeg.length);
            // JPEG SOI / EOI 标记
            assertTrue((jpeg[0] & 0xFF) == 0xFF && (jpeg[1] & 0xFF) == 0xD8, "缺少 JPEG SOI 头");
            assertTrue((jpeg[jpeg.length - 2] & 0xFF) == 0xFF && (jpeg[jpeg.length - 1] & 0xFF) == 0xD9,
                    "缺少 JPEG EOI 尾");
        } finally {
            decoder.close();
        }
    }

    /** 用默认 H.264 编码器（GPL 构建通常为 libx264）合成若干 I 帧 Annex-B 包。 */
    private static List<byte[]> encodeH264AnnexB(int width, int height, int frames) {
        AVCodec codec = avcodec_find_encoder(AV_CODEC_ID_H264);
        if (codec == null) {
            return List.of();
        }
        AVCodecContext ctx = avcodec_alloc_context3(codec);
        AVFrame frame = av_frame_alloc();
        AVPacket pkt = av_packet_alloc();
        List<byte[]> out = new ArrayList<>();
        try {
            ctx.width(width);
            ctx.height(height);
            ctx.pix_fmt(AV_PIX_FMT_YUV420P);
            ctx.time_base(new AVRational().num(1).den(25));
            ctx.framerate(new AVRational().num(25).den(1));
            int rc = avcodec_open2(ctx, codec, (AVDictionary) null);
            if (rc < 0) {
                return List.of();
            }

            frame.width(width);
            frame.height(height);
            frame.format(AV_PIX_FMT_YUV420P);
            rc = av_frame_get_buffer(frame, 32);
            assertTrue(rc >= 0, "分配测试帧缓冲失败 rc=" + rc);

            for (int i = 0; i < frames; i++) {
                assertTrue(av_frame_make_writable(frame) >= 0);
                fillTestFrame(frame, width, height, i);
                frame.pts(i);
                frame.key_frame(1);
                frame.pict_type(AV_PICTURE_TYPE_I);
                if (avcodec_send_frame(ctx, frame) < 0) {
                    return List.of();
                }
                drain(ctx, pkt, out);
            }
            avcodec_send_frame(ctx, null);
            drain(ctx, pkt, out);
            return out;
        } finally {
            av_packet_free(pkt);
            av_frame_free(frame);
            avcodec_free_context(ctx);
        }
    }

    private static void drain(AVCodecContext ctx, AVPacket pkt, List<byte[]> out) {
        while (avcodec_receive_packet(ctx, pkt) >= 0) {
            byte[] copy = new byte[pkt.size()];
            pkt.data().get(copy);
            out.add(copy);
            av_packet_unref(pkt);
        }
    }

    /** 填充渐变 YUV420P 数据，保证内容非纯色且每帧不同。 */
    private static void fillTestFrame(AVFrame frame, int width, int height, int index) {
        BytePointer yPlane = frame.data(0);
        int yStride = frame.linesize(0);
        for (int row = 0; row < height; row++) {
            byte[] line = new byte[width];
            for (int x = 0; x < width; x++) {
                line[x] = (byte) ((x + row + index * 20) & 0xFF);
            }
            yPlane.position((long) row * yStride);
            yPlane.put(line, 0, width);
        }
        byte[] chromaLine = new byte[width / 2];
        Arrays.fill(chromaLine, (byte) 128);
        BytePointer uPlane = frame.data(1);
        BytePointer vPlane = frame.data(2);
        int cStride = frame.linesize(1);
        for (int row = 0; row < height / 2; row++) {
            uPlane.position((long) row * cStride);
            uPlane.put(chromaLine, 0, width / 2);
            vPlane.position((long) row * cStride);
            vPlane.put(chromaLine, 0, width / 2);
        }
    }
}
