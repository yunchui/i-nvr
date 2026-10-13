package com.invr.record;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVOutputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy;
import static org.bytedeco.ffmpeg.global.avformat.AVFMT_GLOBALHEADER;
import static org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE;
import static org.bytedeco.ffmpeg.global.avformat.av_find_input_format;
import static org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_write_trailer;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info;
import static org.bytedeco.ffmpeg.global.avformat.avformat_free_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_new_stream;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_write_header;
import static org.bytedeco.ffmpeg.global.avformat.avio_closep;
import static org.bytedeco.ffmpeg.global.avformat.avio_open;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;

/**
 * 进程内 FFmpeg 流拷贝封装的可行性原型（spike）：把 H.265 裸流（Annex-B）remux 为 MP4。
 *
 * <p>验证 bytedeco-ffmpeg native 可加载、hevc 裸流 demuxer 能自动生成 hvcC/AVCC、
 * mp4 muxer 能产出可播放文件。正式录像器在此基础上改为内存输入并加入 Opus 音轨与分段。</p>
 */
public final class Mp4Remuxer {

    private static final Logger log = LoggerFactory.getLogger(Mp4Remuxer.class);

    private Mp4Remuxer() {
    }

    public static void remuxFile(String inPath, String outPath) throws Exception {
        av_log_set_level(AV_LOG_WARNING);

        // ---- 输入：H.265 Annex-B 裸流（demuxer 自动生成 hvcC、转 AVCC）----
        AVFormatContext inCtx = avformat_alloc_context();
        check(avformat_open_input(inCtx, inPath, av_find_input_format("hevc"), (AVDictionary) null),
                "avformat_open_input");
        check(avformat_find_stream_info(inCtx, (AVDictionary) null), "find_stream_info");

        int videoIdx = -1;
        for (int i = 0; i < inCtx.nb_streams(); i++) {
            AVStream st = inCtx.streams(i);
            if (st.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                videoIdx = i;
                break;
            }
        }
        if (videoIdx < 0) {
            throw new IOException("输入未找到视频流");
        }
        AVStream inVideo = inCtx.streams(videoIdx);
        log.info("输入视频 codec_id={} {}x{} extradata={}B time_base={}/{}",
                inVideo.codecpar().codec_id(), inVideo.codecpar().width(), inVideo.codecpar().height(),
                inVideo.codecpar().extradata_size(),
                inVideo.time_base().num(), inVideo.time_base().den());

        // ---- 输出：MP4 ----
        AVFormatContext outCtx = new AVFormatContext(null);
        check(avformat_alloc_output_context2(outCtx, (AVOutputFormat) null, "mp4", outPath),
                "alloc_output_context2");

        AVStream outVideo = avformat_new_stream(outCtx, (AVCodec) null);
        check(avcodec_parameters_copy(outVideo.codecpar(), inVideo.codecpar()), "parameters_copy");
        outVideo.codecpar().codec_tag(0);
        // 流拷贝：hevc 裸流 demuxer 已生成 hvcC extradata（含 GLOBAL_HEADER 标志），直接复制即可
        outVideo.time_base(inVideo.time_base());

        AVIOContext pb = new AVIOContext(null);
        check(avio_open(pb, outPath, AVIO_FLAG_WRITE), "avio_open");
        outCtx.pb(pb);

        check(avformat_write_header(outCtx, (AVDictionary) null), "write_header");

        AVPacket pkt = av_packet_alloc();
        long frames = 0;
        try {
            while (av_read_frame(inCtx, pkt) >= 0) {
                if (pkt.stream_index() == videoIdx) {
                    av_packet_rescale_ts(pkt, inVideo.time_base(), outVideo.time_base());
                    pkt.stream_index(outVideo.index());
                    check(av_interleaved_write_frame(outCtx, pkt), "write_frame");
                    frames++;
                }
                av_packet_unref(pkt);
            }
        } finally {
            av_packet_unref(pkt);
        }
        av_write_trailer(outCtx);
        av_packet_free(pkt);

        avio_closep(outCtx.pb());
        avformat_free_context(outCtx);
        avformat_close_input(inCtx);
        log.info("remux 完成：{} 帧 -> {}", frames, outPath);
    }

    private static void check(int rc, String what) throws IOException {
        if (rc < 0) {
            throw new IOException(what + " 失败 rc=" + rc);
        }
    }
}
