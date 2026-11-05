package com.invr.codec;

import com.invr.miss.MissPacket;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MJPEG;
import static org.bytedeco.ffmpeg.global.avcodec.av_new_packet;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUVJ420P;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;
import static org.bytedeco.ffmpeg.global.avutil.av_strerror;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;
import static org.bytedeco.ffmpeg.global.swscale.sws_freeContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_getContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale_frame;

/**
 * 一次性视频截帧解码器：在 {@link com.invr.hub.StreamSink} 的专属单派发线程上按序喂入
 * Annex-B 视频包（H.264/H.265），解出<strong>第一帧</strong>后经 swscale 转 YUVJ420P，
 * 再由 MJPEG 编码器输出一张完整 JPEG。
 *
 * <p>设备推流为 Annex-B 且 IDR 包内联 VPS/SPS/PPS（StreamHub 对新订阅者先回放整个 GOP 缓存，
 * 首个视频包即关键帧），解码器可直接吃带内参数集，无需构造 extradata。</p>
 *
 * <p>实例为单次请求私有、不在线程间共享：所有 FFmpeg 调用只发生在订阅派发线程上；
 * {@link #close()} 与回调方法均 synchronized，保证退订超时后释放 native 资源时不会与
 * 正在进行的解码并发。MJPEG 编码为一次性临时资源，编码结束（无论成败）立即释放。</p>
 */
public final class SnapshotDecoder {

    private static final Logger log = LoggerFactory.getLogger(SnapshotDecoder.class);

    static {
        av_log_set_level(AV_LOG_WARNING);
    }

    private AVCodecContext decoder;
    private AVPacket packet;
    private AVFrame decFrame;
    private boolean opened;
    private boolean finished;

    /** 上游重连（新 generation）：清空解码器缓存，随后到达的新 IDR 可独立解码。 */
    public synchronized void reset() {
        if (opened && decoder != null) {
            avcodec_flush_buffers(decoder);
        }
    }

    /**
     * 喂入一个媒体包；音频/非视频包返回 null。
     *
     * @return 解出首帧并完成 JPEG 编码时返回图片字节；尚需更多包时返回 null
     */
    public synchronized byte[] accept(MissPacket pkt) {
        if (finished || pkt == null || !pkt.isVideo() || pkt.payload() == null || pkt.payload().length == 0) {
            return null;
        }
        ensureOpen(pkt.isH265());

        byte[] payload = pkt.payload();
        int fillRc = av_new_packet(packet, payload.length);
        if (fillRc < 0) {
            throw new IllegalStateException("截帧分配解码包失败：" + ffmpegError(fillRc));
        }
        try {
            // av_new_packet 已分配好 packet.data 容量，直接拷贝 Annex-B 负载
            packet.data().put(payload, 0, payload.length);
            int sendRc = avcodec_send_packet(decoder, packet);
            if (sendRc < 0) {
                // 个别包不被解码器接受时跳过，等待后续 IDR；不中断整次截帧
                log.debug("截帧解码送包返回 {}，跳过该包", ffmpegError(sendRc));
                return null;
            }
        } finally {
            av_packet_unref(packet);
        }

        // 一次送包可能输出 0..N 帧，取最早解出的一帧即可
        int rc = avcodec_receive_frame(decoder, decFrame);
        if (rc < 0) {
            // EAGAIN/需要更多包：等待下一个视频包
            return null;
        }
        try {
            byte[] jpeg = encodeJpeg(decFrame);
            finished = true;
            return jpeg;
        } finally {
            // decFrame 每次接收前需 unref；本实例拿到首帧即结束，close() 会整体释放，
            // 这里显式 unref 避免任何路径下 native 帧引用残留
            org.bytedeco.ffmpeg.global.avutil.av_frame_unref(decFrame);
        }
    }

    /** 按首个视频包的编码类型懒初始化解码器。 */
    private void ensureOpen(boolean h265) {
        if (opened) {
            return;
        }
        int codecId = h265 ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264;
        AVCodec codec = avcodec_find_decoder(codecId);
        if (codec == null) {
            throw new IllegalStateException("未找到 " + (h265 ? "H.265" : "H.264") + " 解码器");
        }
        decoder = avcodec_alloc_context3(codec);
        if (decoder == null) {
            throw new IllegalStateException("分配视频解码器上下文失败");
        }
        int rc = avcodec_open2(decoder, codec, (org.bytedeco.ffmpeg.avutil.AVDictionary) null);
        if (rc < 0) {
            avcodec_free_context(decoder);
            decoder = null;
            throw new IllegalStateException("打开视频解码器失败：" + ffmpegError(rc));
        }
        packet = av_packet_alloc();
        decFrame = av_frame_alloc();
        if (packet == null || decFrame == null) {
            throw new IllegalStateException("分配解码 packet/frame 失败");
        }
        opened = true;
        log.info("截帧解码器已打开（{}）", h265 ? "H.265" : "H.264");
    }

    /**
     * 把解码帧缩放转换为 YUVJ420P（MJPEG 编码器通用输入格式），编码为单张 JPEG。
     * 编码器、缩放器与目标帧均为一次性资源，方法结束即释放。
     */
    private byte[] encodeJpeg(AVFrame src) {
        int width = src.width();
        int height = src.height();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("解码帧分辨率无效：" + width + "x" + height);
        }

        AVCodec jpegCodec = avcodec_find_encoder(AV_CODEC_ID_MJPEG);
        if (jpegCodec == null) {
            throw new IllegalStateException("未找到 MJPEG 编码器");
        }
        AVCodecContext enc = avcodec_alloc_context3(jpegCodec);
        AVFrame dst = av_frame_alloc();
        AVPacket encPkt = av_packet_alloc();
        SwsContext sws = null;
        try {
            enc.width(width);
            enc.height(height);
            enc.pix_fmt(AV_PIX_FMT_YUVJ420P);
            enc.time_base(new AVRational().num(1).den(25));
            int rc = avcodec_open2(enc, jpegCodec, (org.bytedeco.ffmpeg.avutil.AVDictionary) null);
            if (rc < 0) {
                throw new IllegalStateException("打开 MJPEG 编码器失败：" + ffmpegError(rc));
            }

            dst.width(width);
            dst.height(height);
            dst.format(AV_PIX_FMT_YUVJ420P);
            rc = av_frame_get_buffer(dst, 32);
            if (rc < 0) {
                throw new IllegalStateException("分配 JPEG 帧缓冲失败：" + ffmpegError(rc));
            }

            sws = sws_getContext(width, height, src.format(),
                    width, height, AV_PIX_FMT_YUVJ420P,
                    SWS_BILINEAR, null, null, (double[]) null);
            if (sws == null) {
                throw new IllegalStateException("创建图像缩放上下文失败");
            }
            rc = sws_scale_frame(sws, dst, src);
            if (rc < 0) {
                throw new IllegalStateException("像素格式转换失败：" + ffmpegError(rc));
            }
            dst.pts(0);

            rc = avcodec_send_frame(enc, dst);
            if (rc < 0) {
                throw new IllegalStateException("MJPEG 送帧失败：" + ffmpegError(rc));
            }
            rc = avcodec_receive_packet(enc, encPkt);
            if (rc < 0) {
                throw new IllegalStateException("MJPEG 收包失败：" + ffmpegError(rc));
            }
            byte[] jpeg = new byte[encPkt.size()];
            encPkt.data().get(jpeg);
            return jpeg;
        } finally {
            if (sws != null) {
                sws_freeContext(sws);
            }
            av_packet_unref(encPkt);
            av_packet_free(encPkt);
            av_frame_free(dst);
            avcodec_free_context(enc);
        }
    }

    /** 释放本次截帧占用的全部解码侧 native 资源；可重复调用。 */
    public synchronized void close() {
        finished = true;
        if (packet != null) {
            av_packet_free(packet);
            packet = null;
        }
        if (decFrame != null) {
            av_frame_free(decFrame);
            decFrame = null;
        }
        if (decoder != null) {
            avcodec_free_context(decoder);
            decoder = null;
        }
        opened = false;
    }

    /** 把 FFmpeg 负错误码翻译为可读文本，失败时回退原始码值。 */
    private static String ffmpegError(int rc) {
        byte[] buf = new byte[256];
        try {
            int n = av_strerror(rc, buf, buf.length);
            if (n == 0) {
                int len = 0;
                while (len < buf.length && buf[len] != 0) {
                    len++;
                }
                return rc + " " + new String(buf, 0, len, StandardCharsets.UTF_8);
            }
        } catch (Exception ignore) {
            // 部分平台错误码无字符串，回退数字
        }
        return "rc=" + rc;
    }
}
