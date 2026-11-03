package com.invr.hls;

import com.invr.codec.VideoConfig;
import com.invr.hub.StreamSink;
import com.invr.miss.MissCommands;
import com.invr.miss.MissPacket;
import com.invr.miss.NalUnits;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVOutputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVAudioFifo;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacpp.BytePointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS;
import static org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY;
import static org.bytedeco.ffmpeg.global.avcodec.av_new_packet;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_write_trailer;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2;
import static org.bytedeco.ffmpeg.global.avformat.avformat_free_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_new_stream;
import static org.bytedeco.ffmpeg.global.avformat.avformat_write_header;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.AV_CH_LAYOUT_STEREO;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP;
import static org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_free;
import static org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_read;
import static org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_realloc;
import static org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_size;
import static org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_write;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_unref;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;
import static org.bytedeco.ffmpeg.global.avutil.av_mallocz;

/**
 * 网页 HLS 预览消费者：订阅 StreamHub，用 FFmpeg 的 hls muxer 产出 <b>fMP4（CMAF）</b>直播：
 * {@code init.mp4}（解码器配置，含 hvcC/avcC 与 AAC esds）+ {@code seg-N.m4s}（媒体分片）+
 * {@code index.m3u8}（含 {@code #EXT-X-MAP}），落于 {@code <录像根>/_hls/<did>/}。
 *
 * <p><b>为何用 fMP4 而非 MPEG-TS：</b>同一路 H.265，浏览器（hls.js 与 Edge/Safari 原生 HLS）
 * 对 MPEG-TS 内 HEVC 的解封装/解码支持不完整（实测有声音、黑屏、不报错），而对 fMP4 内 HEVC
 * 完整支持（实测可正常出画面）。</p>
 *
 * <ul>
 *   <li>视频 H.265/H.264 <b>原样流拷贝</b>：从 VPS/SPS/PPS（或 SPS/PPS）构造 hvcC/avcC 写入
 *       init.mp4，帧数据剔除参数集后转 4 字节长度前缀 AVCC 喂入，不重编码；</li>
 *   <li>音频 Opus 解码后编码为 AAC（浏览器 fMP4 通用），转码仅音频；初始化/转码失败自动静音；</li>
 *   <li>整条直播一个 hls muxer、一条连续时间轴（分片由 muxer 在 IDR 边界按 hls_time 自动切，
 *       独立分片、自带 EXT-X-MAP），重连由 {@link #normalize(long)} 消 gap 并等下一个 IDR 续帧；</li>
 *   <li>旧分片由 muxer 按滑动窗口自动删除；空闲退订时整体 purge。</li>
 * </ul>
 * 与 RTSP/录像共享同一 StreamHub，多标签预览同一摄像头仍只占一条上游。
 */
public final class HlsSink implements StreamSink {

    private static final Logger log = LoggerFactory.getLogger(HlsSink.class);
    private static final AVRational TB_90K = new AVRational().num(1).den(90_000);
    private static final AVRational TB_MS = new AVRational().num(1).den(1_000);
    private static final AVRational TB_AUDIO = new AVRational().num(1).den(48_000);
    private static final int AUDIO_RATE = 48_000;
    private static final Pattern SEG_NAME = Pattern.compile("seg-\\d+\\.m4s");

    private final String did;
    private final File dir;
    private final String baseUrl;
    private final int windowSize;
    private final long targetSec;
    private final AudioPipe audio;

    private Muxer muxer;
    private boolean h265 = true;

    private int currentGen;
    private boolean waitKey = true;
    private byte[] pendingConfig;
    private int[] pendingDim;

    // 全局时间戳归一（重连消 gap）
    private long baseDevTs = -1;
    private long tsOffset;
    private long lastOutMs = -1;
    private long lastVideoMs = -1;

    private final AtomicLong videoPackets = new AtomicLong();

    public HlsSink(String did, File dir, int windowSize, long targetSec, boolean wantAudio) {
        this.did = did;
        this.dir = dir;
        this.baseUrl = dir.getAbsolutePath().replace('\\', '/');
        this.windowSize = Math.max(3, windowSize);
        this.targetSec = targetSec;
        this.audio = new AudioPipe(wantAudio);
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("[{}] HLS 目录创建失败：{}", did, dir.getAbsolutePath());
        }
    }

    @Override
    public void onReset(int generation) {
        currentGen = generation;
        // fMP4 muxer 保持连续；重连后丢弃非关键帧，等 Hub 从 GOP 侧载的下一个 IDR 再续帧
        waitKey = true;
        log.info("[{}] HLS 感知上游重连，等待新 IDR 续接 fMP4 时间轴", did);
    }

    @Override
    public void onPacket(MissPacket pkt, int generation) {
        if (generation != currentGen) {
            return;
        }
        try {
            if (pkt.isVideo()) {
                handleVideo(pkt);
            } else if (pkt.codecId() == MissCommands.CODEC_OPUS) {
                handleAudio(pkt);
            }
        } catch (Throwable t) {
            log.warn("[{}] HLS 处理包异常（已跳过）：{}", did, t.toString());
        }
    }

    private void handleVideo(MissPacket pkt) {
        h265 = pkt.isH265();
        long outMs = normalize(pkt.timestampMs());
        byte[] au = pkt.payload();

        // 提取 Annex-B 参数集（VPS/SPS/PPS 带起始码拼接）作为 extradata，并解析分辨率。
        // HLS(fMP4) 的 movenc 见到“以起始码开头的 Annex-B extradata”会自动挂 hevc_mp4 / avc
        // bitstream filter：把每个 packet 的 Annex-B 帧转成 AVCC、把参数集生成 hvcC/avcC 写入
        // init.mp4——这正是 ffmpeg 命令行 `-c copy -f hls -hls_segment_type fmp4` 的标准路径，
        // 已用真机裸流验证 ffmpeg -err_detect explode 严格解码零错误。
        byte[] cfg = buildAnnexBParams(h265, au);
        if (cfg != null) {
            pendingConfig = cfg;
            if (h265) {
                int[] dim = VideoConfig.parseHevcDimensionsFromAu(au);
                if (dim != null) {
                    pendingDim = dim;
                }
            }
        }

        // 帧直接用原始 Annex-B 访问单元（含起始码；IDR 自带 VPS/SPS/PPS），整包作为一个 packet，
        // 交给 movenc 的 BSF 完成 Annex-B→AVCC。绝不能在 Java 侧手工转 AVCC + 手工 hvcC：实测
        // hls muxer 会对其再做一次误转换，在长度前缀字节里误找起始码，造成 NAL 边界错位、解码器
        // 把图像数据当 slice 头解析（随机 NAL 类型 / 碎片帧）→ 浏览器 MEDIA_ERR_DECODE 黑屏。
        boolean key = NalUnits.containsIdr(h265, au);

        // 冷启动：等到首个含参数集的 IDR 帧才启动 muxer（其之前的帧丢弃）
        if (muxer == null) {
            if (pendingConfig == null || !key) {
                return;
            }
            try {
                muxer = new Muxer(pendingConfig, pendingDim, h265);
                muxer.open();
                waitKey = false;
                log.info("[{}] HLS(fMP4) muxer 已启动（{} {}x{}）", did,
                        h265 ? "H.265" : "H.264",
                        pendingDim == null ? 0 : pendingDim[0],
                        pendingDim == null ? 0 : pendingDim[1]);
            } catch (Throwable t) {
                muxer = null;
                log.error("[{}] HLS(fMP4) 启动失败：{}", did, t.toString());
                return;
            }
        }

        // 重连后只在 IDR（闭合 GOP）续接
        if (waitKey) {
            if (!key) {
                return;
            }
            waitKey = false;
            log.info("[{}] HLS 已在新 IDR 续接时间轴（t={}ms）", did, outMs);
        }

        // 一包一帧，时间戳直接用设备时间（normalize 已全局归一）；+1 兜底保证 dts 严格单调
        if (outMs <= lastVideoMs) {
            outMs = lastVideoMs + 1;
        }
        lastVideoMs = outMs;

        // 喂原始 Annex-B AU，由 movenc 自动挂的 hevc_mp4/avc BSF 转成 AVCC 写入 fMP4
        muxer.writeVideo(au, outMs, key);
        videoPackets.incrementAndGet();
    }

    private void handleAudio(MissPacket pkt) {
        if (muxer != null && !waitKey && muxer.aStream != null && audio.isUp()) {
            // 用与视频同一基准的归一化设备时间（只读，不改动视频维护的 tsOffset/lastOutMs），
            // 让音频 PTS 锚定设备时钟，从根上消除两条独立时间轴的错位。
            long devMs = normalizeView(pkt.timestampMs());
            if (!audio.feed(pkt.payload(), devMs, muxer.ctx, muxer.aStream)) {
                log.warn("[{}] HLS 音频转码不可用，预览降级为静音（视频不受影响）", did);
            }
        }
    }

    /**
     * 等待 init.mp4 与“已完成并写入播放列表”的首个分片。
     *
     * <p>不能用目录里是否出现 seg-N.m4s 判断——ffmpeg 会先创建（打开）正在写入的分片文件，
     * 此刻它还不完整、且 index.m3u8 尚未列入它；若此刻返回，播放器拿到的是只有头部、没有任何
     * 分片条目的直播 m3u8，原生 HLS（Edge MediaFoundation）会直接 MEDIA_ERR_SRC_NOT_SUPPORTED。
     * 必须确认 index.m3u8 文本里已经列出至少一个 .m4s 分片（ffmpeg 在分片关闭切换时才写入）。</p>
     */
    public boolean awaitFirstSegment(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            File init = new File(dir, "init.mp4");
            File pl = new File(dir, "index.m3u8");
            if (init.isFile() && pl.isFile()) {
                try {
                    String text = Files.readString(pl.toPath(), StandardCharsets.UTF_8);
                    if (text.contains(".m4s")) {
                        return true;
                    }
                } catch (Exception ignore) {
                    // m3u8 正被 ffmpeg 重写，下一轮再读
                }
            }
            Thread.sleep(200);
        }
        return false;
    }

    /** 读取 m3u8 内容；把可能出现的磁盘绝对路径归一为相对 basename（hls.js 按同目录请求）。 */
    public byte[] playlistBytes() {
        File pl = new File(dir, "index.m3u8");
        if (!pl.isFile()) {
            return "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n"
                    .getBytes(StandardCharsets.UTF_8);
        }
        try {
            String text = Files.readString(pl.toPath(), StandardCharsets.UTF_8);
            text = text.replace(baseUrl + "/", "").replace(baseUrl + "\\", "");
            return text.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取 m3u8 失败：" + e.getMessage());
        }
    }

    /** 按白名单返回 init.mp4 / seg-N.m4s 文件，拒绝任何路径穿越。 */
    public File mediaFile(String name) {
        boolean ok = "init.mp4".equals(name) || SEG_NAME.matcher(name).matches();
        if (!ok) {
            return null;
        }
        File f = new File(dir, name);
        try {
            String root = dir.getCanonicalPath();
            String target = f.getCanonicalPath();
            if (!target.equals(new File(root, name).getCanonicalPath())) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return f.isFile() ? f : null;
    }

    public long videoPackets() {
        return videoPackets.get();
    }

    public synchronized void shutdown() {
        if (muxer != null) {
            muxer.close();
            muxer = null;
        }
        audio.close();
    }

    public void purge() {
        shutdown();
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.delete()) {
                    log.warn("[{}] 无法清理 HLS 文件 {}", did, f.getName());
                }
            }
        }
        if (dir.exists() && !dir.delete()) {
            log.debug("[{}] HLS 目录非空或已删除：{}", did, dir.getAbsolutePath());
        }
    }

    private long normalize(long devTs) {
        if (baseDevTs < 0) {
            baseDevTs = devTs;
        }
        long out = devTs - baseDevTs + tsOffset;
        if (out < lastOutMs || out - lastOutMs > 1_000) {
            tsOffset += lastOutMs - out;
            out = lastOutMs;
        }
        if (out > lastOutMs) {
            lastOutMs = out;
        }
        return out;
    }

    /**
     * 只读归一化：用当前 baseDevTs/tsOffset 把设备毫秒换算为与视频同一基准的内容毫秒，
     * 不更新共享的 tsOffset/lastOutMs，避免音频包干扰视频时间戳状态。视频在断流时调整
     * tsOffset 后，本方法读取同一偏移，音频自动跟随视频的消 gap 平移。
     */
    private long normalizeView(long devTs) {
        if (baseDevTs < 0) {
            return 0;
        }
        return devTs - baseDevTs + tsOffset;
    }

    /**
     * 从访问单元提取参数集 NAL，按 Annex-B（每个 NAL 前加 4 字节起始码 00 00 00 01）拼接成
     * extradata。H.265 收集 VPS(32)/SPS(33)/PPS(34)，H.264 收集 SPS(7)/PPS(8)，缺任一返回 null。
     *
     * <p>movenc 见到“以起始码开头”的 extradata 即判定输入为 Annex-B，自动挂 hevc_mp4 / avc
     * bitstream filter：把参数集转成 hvcC/avcC 写入 init.mp4、把每帧转成 AVCC 写入 mdat。</p>
     */
    private static byte[] buildAnnexBParams(boolean h265, byte[] au) {
        byte[] vps = null, sps = null, pps = null;
        for (NalUnits.Nal n : NalUnits.split(h265, au)) {
            byte[] nal = n.data;
            if (nal.length == 0) {
                continue;
            }
            int t = n.type;
            if (h265) {
                if (t == 32 && vps == null) {
                    vps = nal;
                } else if (t == 33 && sps == null) {
                    sps = nal;
                } else if (t == 34 && pps == null) {
                    pps = nal;
                }
            } else {
                if (t == 7 && sps == null) {
                    sps = nal;
                } else if (t == 8 && pps == null) {
                    pps = nal;
                }
            }
        }
        if (sps == null || pps == null || (h265 && vps == null)) {
            return null;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream(128);
        if (h265) {
            writeAnnexNal(o, vps);
        }
        writeAnnexNal(o, sps);
        writeAnnexNal(o, pps);
        return o.toByteArray();
    }

    private static void writeAnnexNal(ByteArrayOutputStream o, byte[] nal) {
        o.write(0);
        o.write(0);
        o.write(0);
        o.write(1);
        o.write(nal, 0, nal.length);
    }

    /**
     * 单个持续运行的 fMP4 HLS muxer：视频喂 Annex-B 原始访问单元，由 movenc 自动挂 BSF 转
     * hvcC/avcC + AVCC；音频写入共享 AAC 管线。init.mp4 / seg-N.m4s / index.m3u8 由 muxer 维护。
     */
    private final class Muxer {
        AVFormatContext ctx;
        AVStream vStream;
        AVStream aStream;
        AVPacket videoPkt;
        BytePointer videoConfigRef;

        private final byte[] pendingConfig0;
        private final int[] dim0;
        private final boolean hevc0;

        Muxer(byte[] config, int[] dim, boolean hevc) {
            this.pendingConfig0 = config;
            this.dim0 = dim;
            this.hevc0 = hevc;
        }

        void open() throws Exception {
            String playlist = baseUrl + "/index.m3u8";
            ctx = new AVFormatContext(null);
            int rc = avformat_alloc_output_context2(ctx, (AVOutputFormat) null, "hls", playlist);
            if (rc < 0) {
                throw new IllegalStateException("alloc hls output 失败 rc=" + rc);
            }

            // 视频流：extradata 给“以起始码开头”的 Annex-B 参数集（VPS/SPS/PPS 或 SPS/PPS）。
            // movenc 据此判定输入为 Annex-B，write_header 时自动挂 hevc_mp4 / avc bitstream
            // filter，把参数集生成 hvcC/avcC 写入 init.mp4、把每个 Annex-B packet 转成 AVCC。
            // 切勿在此给 hvcC/avcC 并手工喂 AVCC 帧——hls muxer 会二次误转换导致 NAL 错位黑屏。
            vStream = avformat_new_stream(ctx, (AVCodec) null);
            var vcp = vStream.codecpar();
            vcp.codec_type(AVMEDIA_TYPE_VIDEO);
            vcp.codec_id(hevc0 ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264);
            // fMP4 中 HEVC/H264 用 hvc1/avc1 fourcc（参数集在 init 的 hvcC/avcC 内），这是 Safari/
            // Edge 原生 HLS 兼容性最好的写法；MKTAG('h','v','c','1')=0x31637668，avc1=0x31637661。
            vcp.codec_tag(hevc0 ? 0x31637668 : 0x31637661);
            vcp.width(dim0 == null ? 0 : dim0[0]);
            vcp.height(dim0 == null ? 0 : dim0[1]);
            vcp.format(AV_PIX_FMT_YUV420P);
            videoConfigRef = new BytePointer(av_mallocz(pendingConfig0.length)).capacity(pendingConfig0.length);
            videoConfigRef.position(0).put(pendingConfig0, 0, pendingConfig0.length);
            vcp.extradata(videoConfigRef);
            vcp.extradata_size(pendingConfig0.length);
            vStream.time_base(TB_MS);

            // 音频流：AAC（esds/AudioSpecificConfig 由 encoder 参数拷贝得到）
            if (audio.isUp()) {
                aStream = avformat_new_stream(ctx, (AVCodec) null);
                avcodec_parameters_from_context(aStream.codecpar(), audio.encoder());
                aStream.time_base(audio.encoder().time_base());
            }

            AVDictionary opt = new AVDictionary();
            av_dict_set(opt, "hls_segment_type", "fmp4", 0);
            av_dict_set(opt, "hls_time", String.valueOf(targetSec), 0);
            av_dict_set(opt, "hls_list_size", String.valueOf(windowSize), 0);
            av_dict_set(opt, "hls_delete_threshold", String.valueOf(windowSize + 2), 0);
            av_dict_set(opt, "hls_flags", "delete_segments+independent_segments", 0);
            // init 文件名必须给 basename：ffmpeg 会将其拼到 m3u8 所在目录；若给 Windows 绝对
            // 路径（"C:/..." 不以 "/" 开头，被当作相对路径）会二次拼接成非法路径，write_header
            // 返回 EINVAL。分片文件名则给全路径（hls_start 直接打开，不做目录拼接）。
            av_dict_set(opt, "hls_fmp4_init_filename", "init.mp4", 0);
            av_dict_set(opt, "hls_segment_filename", baseUrl + "/seg-%d.m4s", 0);

            // hls muxer 带 AVFMT_NOFILE 标志：playlist / init.mp4 / seg-N.m4s 全部由 muxer
            // 内部 io_open 自行管理，调用方不能预先 avio_open pb（否则 write_header 失败）。
            int wh = avformat_write_header(ctx, opt);
            av_dict_free(opt);
            if (wh < 0) {
                throw new IllegalStateException("hls(fMP4) write_header 失败 rc=" + wh);
            }
            videoPkt = av_packet_alloc();
        }

        /** 写入一个视频访问单元。{@code au} 为原始 Annex-B 字节（含起始码），由 movenc 的 BSF 转 AVCC。 */
        void writeVideo(byte[] au, long globalMs, boolean key) {
            av_new_packet(videoPkt, au.length);
            videoPkt.data().put(au, 0, au.length);
            videoPkt.pts(globalMs);
            videoPkt.dts(globalMs);
            videoPkt.flags(key ? AV_PKT_FLAG_KEY : 0);
            videoPkt.stream_index(vStream.index());
            av_packet_rescale_ts(videoPkt, TB_MS, vStream.time_base());
            av_interleaved_write_frame(ctx, videoPkt);
            av_packet_unref(videoPkt);
        }

        void close() {
            try {
                av_write_trailer(ctx);
            } catch (Throwable t) {
                log.debug("[{}] HLS 写 trailer 异常：{}", did, t.toString());
            }
            if (videoPkt != null) {
                av_packet_free(videoPkt);
                videoPkt = null;
            }
            if (ctx != null) {
                // NOFILE：playlist/分片由 muxer 在 trailer/free 时自行关闭
                avformat_free_context(ctx);
                ctx = null;
            }
            videoConfigRef = null;
        }
    }

    /**
     * 跨分片持久的音频转码管线：Opus 解码 → FIFO 凑 AAC 帧（1024）→ AAC 编码。
     * 整条直播只初始化一次，保证音频时间戳连续、无编码器 priming 空隙。
     */
    private final class AudioPipe {
        private AVCodecContext opusDec;
        private AVCodecContext aacEnc;
        private AVAudioFifo fifo;
        private AVFrame decFrame;
        private AVFrame encFrame;
        private AVPacket packet;
        private long samples;
        private long outSamples;          // 已从 fifo 读出（编码成 AAC）的累计样本位置
        private long inSamples;           // 已写入 fifo 的累计样本位置
        // 样本位置 → 设备 48k 时间的映射点：{写入后样本位置, 该位置设备样本时间}
        private final Deque<long[]> marks = new ArrayDeque<>();
        private boolean up;

        AudioPipe(boolean wantAudio) {
            if (!wantAudio) {
                return;
            }
            try {
                av_log_set_level(AV_LOG_WARNING);
                AVCodec decCodec = avcodec_find_decoder(AV_CODEC_ID_OPUS);
                if (decCodec == null) {
                    throw new IllegalStateException("未找到 Opus 解码器");
                }
                opusDec = avcodec_alloc_context3(decCodec);
                opusDec.sample_rate(AUDIO_RATE);
                opusDec.sample_fmt(AV_SAMPLE_FMT_FLTP);
                opusDec.channels(2);
                opusDec.channel_layout(AV_CH_LAYOUT_STEREO);
                if (avcodec_open2(opusDec, decCodec, (AVDictionary) null) < 0) {
                    throw new IllegalStateException("Opus 解码器打开失败");
                }

                AVCodec encCodec = avcodec_find_encoder(AV_CODEC_ID_AAC);
                if (encCodec == null) {
                    throw new IllegalStateException("未找到 AAC 编码器");
                }
                aacEnc = avcodec_alloc_context3(encCodec);
                aacEnc.bit_rate(128_000);
                aacEnc.sample_rate(AUDIO_RATE);
                aacEnc.sample_fmt(AV_SAMPLE_FMT_FLTP);
                aacEnc.channels(2);
                aacEnc.channel_layout(AV_CH_LAYOUT_STEREO);
                aacEnc.time_base(TB_AUDIO);
                if (avcodec_open2(aacEnc, encCodec, (AVDictionary) null) < 0) {
                    throw new IllegalStateException("AAC 编码器打开失败");
                }

                fifo = av_audio_fifo_alloc(AV_SAMPLE_FMT_FLTP, 2, 1);
                decFrame = av_frame_alloc();
                encFrame = av_frame_alloc();
                packet = av_packet_alloc();
                up = true;
                log.info("[{}] HLS 音频转码就绪（Opus→AAC 48k 立体声）", did);
            } catch (Throwable t) {
                log.warn("[{}] HLS 音频初始化失败，降级静音：{}", did, t.toString());
                up = false;
                close();
            }
        }

        boolean isUp() {
            return up;
        }

        AVCodecContext encoder() {
            return aacEnc;
        }

        /**
         * 喂入一个裸 Opus 包（{@code devMs} 为该包起始的归一化设备毫秒，与视频同基准），
         * 把产出的 AAC 帧按设备时间戳写入 fMP4 muxer 的音频流。
         */
        boolean feed(byte[] opus, long devMs, AVFormatContext ctx, AVStream aStream) {
            if (!up) {
                return false;
            }
            try {
                av_new_packet(packet, opus.length);
                packet.data().put(opus, 0, opus.length);
                int sr = avcodec_send_packet(opusDec, packet);
                av_packet_unref(packet);
                if (sr < 0) {
                    return false;
                }
                // 该 Opus 包起始样本位置与起始设备样本时间（48k 轴，1ms=48 样本）
                long packetStartSample = inSamples;
                long devSampleBase = devMs * 48L;
                while (avcodec_receive_frame(opusDec, decFrame) == 0) {
                    int n = decFrame.nb_samples();
                    // 先记录帧起始位置的设备样本时间（首帧/位置不衔接时需要，保证 pts 精确）
                    if (marks.isEmpty() || marks.peekLast()[0] != inSamples) {
                        marks.addLast(new long[]{inSamples,
                                devSampleBase + (inSamples - packetStartSample)});
                    }
                    if (av_audio_fifo_realloc(fifo, av_audio_fifo_size(fifo) + n) < 0
                            || av_audio_fifo_write(fifo, decFrame.data(), n) < n) {
                        av_frame_unref(decFrame);
                        up = false;
                        return false;
                    }
                    // 记录本解码帧结束位置对应的设备样本时间（样本域内线性，无毫秒舍入）
                    inSamples += n;
                    long devSampleAtEnd = devSampleBase + (inSamples - packetStartSample);
                    marks.addLast(new long[]{inSamples, devSampleAtEnd});
                    av_frame_unref(decFrame);
                    pumpFifo(ctx, aStream);
                }
                return true;
            } catch (Throwable t) {
                up = false;
                log.warn("[{}] HLS 音频转码异常，降级静音：{}", did, t.toString());
                return false;
            }
        }

        /** 查找样本位置 {@code samplePos} 对应的设备 48k 样本时间（相邻映射点间线性插值）。 */
        private long devSampleAt(long samplePos) {
            long[] prev = null;
            Iterator<long[]> it = marks.iterator();
            while (it.hasNext()) {
                long[] m = it.next();
                if (m[0] >= samplePos) {
                    if (prev == null) {
                        return m[1];
                    }
                    long span = m[0] - prev[0];
                    if (span == 0) {
                        return m[1];
                    }
                    double frac = (double) (samplePos - prev[0]) / span;
                    return Math.round(prev[1] + frac * (m[1] - prev[1]));
                }
                prev = m;
            }
            return prev != null ? prev[1] : 0;
        }

        /** 回收已落后于当前播放位置的映射点，始终保留至少一个点用于插值。 */
        private void pruneMarks() {
            while (marks.size() > 2) {
                Iterator<long[]> it = marks.iterator();
                it.next();
                long[] second = it.next();
                if (second[0] < outSamples) {
                    marks.pollFirst();
                } else {
                    break;
                }
            }
        }

        private void pumpFifo(AVFormatContext ctx, AVStream aStream) {
            int fs = aacEnc.frame_size();
            while (av_audio_fifo_size(fifo) >= fs) {
                av_frame_unref(encFrame);
                encFrame.nb_samples(fs);
                encFrame.format(AV_SAMPLE_FMT_FLTP);
                encFrame.channel_layout(AV_CH_LAYOUT_STEREO);
                encFrame.sample_rate(AUDIO_RATE);
                if (av_frame_get_buffer(encFrame, 0) < 0) {
                    break;
                }
                if (av_audio_fifo_read(fifo, encFrame.data(), fs) < fs) {
                    break;
                }
                encFrame.pts(samples);
                samples += fs;
                if (avcodec_send_frame(aacEnc, encFrame) < 0) {
                    break;
                }
                while (avcodec_receive_packet(aacEnc, packet) == 0) {
                    packet.stream_index(aStream.index());
                    // AAC 输出 PTS 锚定与视频同一设备时钟：按当前 AAC 帧起始样本位置查映射，
                    // 得到设备 48k 样本时间，而非独立样本计数。断流后设备时间自然跳变，
                    // 音频跟随视频对齐，两条时间轴不再错位。native AAC 的 priming delay 由
                    // 编码器在内部处理，这里仍保证 pts/dts 非负、严格单调，duration 显式 1024。
                    long devPts = devSampleAt(outSamples);
                    packet.pts(devPts);
                    packet.dts(devPts);
                    packet.duration(fs);
                    outSamples += fs;
                    pruneMarks();
                    // 从编码器 1/48000 换算到音频流实际 time_base
                    av_packet_rescale_ts(packet, TB_AUDIO, aStream.time_base());
                    av_interleaved_write_frame(ctx, packet);
                    av_packet_unref(packet);
                }
            }
        }

        void close() {
            up = false;
            if (fifo != null) {
                av_audio_fifo_free(fifo);
                fifo = null;
            }
            if (decFrame != null) {
                av_frame_free(decFrame);
                decFrame = null;
            }
            if (encFrame != null) {
                av_frame_free(encFrame);
                encFrame = null;
            }
            if (packet != null) {
                av_packet_free(packet);
                packet = null;
            }
            if (opusDec != null) {
                avcodec_free_context(opusDec);
                opusDec = null;
            }
            if (aacEnc != null) {
                avcodec_free_context(aacEnc);
                aacEnc = null;
            }
        }
    }
}
