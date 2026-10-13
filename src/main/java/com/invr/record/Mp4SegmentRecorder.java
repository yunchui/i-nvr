package com.invr.record;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVOutputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacpp.BytePointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS;
import static org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY;
import static org.bytedeco.ffmpeg.global.avcodec.av_new_packet;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE;
import static org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_write_trailer;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2;
import static org.bytedeco.ffmpeg.global.avformat.avformat_free_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_new_stream;
import static org.bytedeco.ffmpeg.global.avformat.avformat_write_header;
import static org.bytedeco.ffmpeg.global.avformat.avio_closep;
import static org.bytedeco.ffmpeg.global.avformat.avio_open;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;
import static org.bytedeco.ffmpeg.global.avutil.av_mallocz;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;

/**
 * 进程内 MP4 分段录像器（流拷贝，不重编码）：H.265/H.264 视频 + Opus 音频。
 *
 * <p>视频侧逐访问单元处理：真机已验证上游每个视频包即一个完整访问单元（约 1:1 对应一帧），
 * 故不经过 ffmpeg 裸流 demuxer / parser（{@code find_stream_info} 预读会吞帧导致时间戳错位，
 * parser 又会丢帧且不导出参数集）。本类直接把 Annex-B 访问单元切成 NAL，手工：
 * <ul>
 *   <li>从 VPS/SPS/PPS 构造 hvcC（或 SPS/PPS 构造 avcC）作为 MP4 extradata；</li>
 *   <li>从 H.265 SPS 解析亮度分辨率；</li>
 *   <li>剔除参数集 NAL，其余 NAL 加 4 字节长度前缀拼成 AVCC 帧。</li>
 * </ul>
 * 设备毫秒时间戳随包直达，严格 1:1，不丢帧。</p>
 *
 * <p>音频手工构造 Opus 音轨（OpusHead + 每包裸 Opus 帧，48kHz、40ms/包）。按固定时长在
 * <b>关键帧（IDR）边界</b>滚动分段，每个 MP4 都以 IDR 开头、可独立播放。所有 ffmpeg 操作集中在
 * 一条录制线程；媒体数据经有界队列喂入，不阻塞 StreamHub 上游。重连造成的时间戳空洞由
 * {@link #normalize(long)} 平移消隙，时间轴保持连续。</p>
 */
public final class Mp4SegmentRecorder {

    private static final Logger log = LoggerFactory.getLogger(Mp4SegmentRecorder.class);

    /** Opus 40ms/包 @48kHz。 */
    private static final int OPUS_FRAME_SAMPLES = 1920;
    private static final int OPUS_SAMPLE_RATE = 48_000;
    private static final AVRational TB_MS = new AVRational().num(1).den(1000);
    private static final AVRational TB_OPUS = new AVRational().num(1).den(OPUS_SAMPLE_RATE);

    private final String did;
    private final File outDir;
    private final long segmentMs;
    private final int videoCodecId;
    private final int audioChannels;

    private final LinkedBlockingQueue<VideoFrame> videoQueue = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<AudioFrame> audioQueue = new LinkedBlockingQueue<>();
    private final AtomicLong segmentCounter = new AtomicLong();
    private static final VideoFrame EOF = new VideoFrame(-1, null);

    // 时间戳归一（仅在 feed 侧单线程调用）
    private long baseDevTs = -1;
    private long tsOffset;
    private long lastOutMs;

    private Thread worker;
    private volatile boolean started;
    private volatile boolean running;
    private volatile Throwable fatal;

    // 每段输出资源（录制线程独占）
    private AVFormatContext outCtx;
    /** 当前正在写入（未封段）的文件名；封段完成或停止后为 null。供列表精确标记“录制中”。 */
    private volatile String currentSegmentName;
    private AVIOContext outPb;
    private AVStream outVideo;
    private AVStream outAudio;
    private BytePointer opusExtradataRef;
    private BytePointer videoConfigRef;
    private byte[] pendingConfig;
    private int[] pendingDim;
    private AVPacket videoPkt;
    private AVPacket audioPkt;
    private long segmentStartMs = -1;
    private long lastVideoRelMs = -1;

    public Mp4SegmentRecorder(String did, File outDir, long segmentSeconds,
                              boolean h265, int audioChannels) throws IOException {
        this.did = did;
        this.outDir = outDir;
        this.segmentMs = segmentSeconds * 1000L;
        this.videoCodecId = h265 ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264;
        this.audioChannels = audioChannels;
        Files.createDirectories(outDir.toPath());
        // 分段序号跨重启接续：扫描本摄像头已有段的最大序号，从其后继续，
        // 避免服务重启后续录又从 001 开始而与旧段序号重复。
        this.segmentCounter.set(scanExistingMaxSegment());
    }

    private static final Pattern SEGMENT_NAME_PATTERN =
            Pattern.compile("^rec-(.*?)-(\\d+)-\\d{8}-\\d{6}\\.mp4$");

    /** 扫描 outDir 中属于本 did 的分段，返回最大序号（无则 0）。 */
    private long scanExistingMaxSegment() {
        File[] files = outDir.listFiles();
        if (files == null) {
            return 0;
        }
        long max = 0;
        for (File f : files) {
            Matcher m = SEGMENT_NAME_PATTERN.matcher(f.getName());
            if (m.matches() && m.group(1).equals(did)) {
                try {
                    max = Math.max(max, Long.parseLong(m.group(2)));
                } catch (NumberFormatException ignore) {
                    // 文件名序号异常则忽略
                }
            }
        }
        return max;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        running = true;
        worker = new Thread(this::run, "mp4-recorder-" + did);
        worker.setDaemon(true);
        worker.start();
    }

    /** 喂入一个视频访问单元（Annex-B，含起始码）与设备毫秒时间戳。 */
    public void feedVideo(byte[] annexB, long devTsMs) {
        if (!running) {
            return;
        }
        long outMs = normalize(devTsMs);
        videoQueue.offer(new VideoFrame(outMs, annexB));
    }

    /** 喂入一个裸 Opus 帧与设备毫秒时间戳。 */
    public void feedAudio(byte[] opus, long devTsMs) {
        if (!running) {
            return;
        }
        long outMs = normalize(devTsMs);
        audioQueue.offer(new AudioFrame(outMs, opus));
    }

    /** 停止并收尾（写出 trailer、关闭所有文件）。 */
    public void stop() {
        if (!started) {
            return;
        }
        running = false;
        videoQueue.offer(EOF);
        try {
            worker.join(15_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (fatal != null) {
            log.warn("[{}] 录像线程异常结束：{}", did, fatal.toString());
        }
    }

    public long segmentCount() {
        return segmentCounter.get();
    }

    /** 当前正在写入的分段文件名；无（尚未开始/已封段/已停止）时返回 null。 */
    public String currentSegmentName() {
        return currentSegmentName;
    }

    // ------------------------------------------------------------------
    // 录制线程
    // ------------------------------------------------------------------

    private void run() {
        av_log_set_level(AV_LOG_WARNING);
        videoPkt = av_packet_alloc();
        audioPkt = av_packet_alloc();

        try {
            while (true) {
                VideoFrame vf = videoQueue.take();
                if (vf == EOF) {
                    break;
                }
                // 上游每个视频包即一个完整访问单元（真机已验证 1:1），直接转 4 字节长度前缀 AVCC
                processAccessUnit(vf.data, vf.outMs);
            }
            // EOF：写尽音频
            flushAudio(Long.MAX_VALUE);
        } catch (Throwable t) {
            fatal = t;
            log.error("[{}] MP4 录像线程异常：{}", did, t.toString(), t);
        } finally {
            closeSegment();
            av_packet_unref(videoPkt);
            av_packet_unref(audioPkt);
            av_packet_free(videoPkt);
            av_packet_free(audioPkt);
            log.info("[{}] 录像结束，共生成 {} 个分段", did, segmentCounter.get());
        }
    }

    /**
     * 处理一个 Annex-B 访问单元：提取/缓存参数集（hvcC/avcC 与分辨率），把 VCL NAL 转成
     * 4 字节长度前缀的 AVCC（参数集 NAL 剔除，已放入 extradata），并判定是否 IDR 关键帧。
     */
    private void processAccessUnit(byte[] annexB, long outMs) throws IOException {
        boolean hevc = videoCodecId == AV_CODEC_ID_HEVC;
        java.util.List<byte[]> nals = splitNals(annexB);
        byte[] cfg = hevc ? buildHevcConfigRecord(annexB) : buildAvcConfigRecord(annexB);
        if (cfg != null) {
            pendingConfig = cfg;
            if (hevc) {
                int[] dim = parseHevcDimensionsFromAu(annexB);
                if (dim != null) {
                    pendingDim = dim;
                }
            }
        }
        java.io.ByteArrayOutputStream avcc = new java.io.ByteArrayOutputStream(annexB.length);
        boolean key = false;
        for (byte[] nal : nals) {
            if (nal.length == 0) {
                continue;
            }
            int t = hevc ? ((nal[0] >> 1) & 0x3F) : (nal[0] & 0x1F);
            boolean param = hevc ? (t == 32 || t == 33 || t == 34) : (t == 7 || t == 8);
            if (param) {
                continue; // VPS/SPS/PPS（或 SPS/PPS）已进入 extradata，帧内不再重复
            }
            if (hevc ? (t == 19 || t == 20) : t == 5) {
                key = true; // IDR
            }
            avcc.write((nal.length >>> 24) & 0xFF);
            avcc.write((nal.length >>> 16) & 0xFF);
            avcc.write((nal.length >>> 8) & 0xFF);
            avcc.write(nal.length & 0xFF);
            avcc.write(nal, 0, nal.length);
        }
        byte[] frame = avcc.toByteArray();
        if (frame.length == 0) {
            return;
        }
        handleVideoFrame(frame, outMs, key);
    }

    private void handleVideoFrame(byte[] avcc, long outMs, boolean key) throws IOException {
        // 首个含参数集的 IDR 到达时懒初始化输出
        if (outCtx == null) {
            if (pendingConfig == null || !key) {
                return;
            }
            openSegment(outMs, pendingConfig);
        } else if (key && outMs - segmentStartMs >= segmentMs) {
            log.info("[{}] 到达 {}s 分段边界（t={}ms），关闭旧段并开新段", did, segmentMs / 1000, outMs);
            // 先把属于旧段（ts<=切点）的音频全部写齐，避免音频超前积压导致 trailer 异常
            flushAudio(outMs);
            closeSegment();
            openSegment(outMs, pendingConfig);
        }

        // 音频与视频严格齐平（不超前），防止交织缓冲长期积压
        flushAudio(outMs);

        long relMs = outMs - segmentStartMs;
        // 丢弃时间戳重复/倒退的冗余视频包（流启动偶发重复副本），保证 dts 严格单调
        if (relMs <= lastVideoRelMs) {
            return;
        }
        lastVideoRelMs = relMs;
        av_new_packet(videoPkt, avcc.length);
        videoPkt.data().put(avcc, 0, avcc.length);
        videoPkt.pts(relMs);
        videoPkt.dts(relMs);
        videoPkt.flags(key ? AV_PKT_FLAG_KEY : 0);
        av_packet_rescale_ts(videoPkt, TB_MS, outVideo.time_base());
        videoPkt.stream_index(outVideo.index());
        int wr = av_interleaved_write_frame(outCtx, videoPkt);
        if (wr < 0) {
            log.warn("[{}] 写视频帧失败 rc={}", did, wr);
        }
        av_packet_unref(videoPkt);
    }

    /** 把输出时间戳不超过 {@code upToMs} 的已缓存音频帧按序写出。 */
    private void flushAudio(long upToMs) {
        if (outCtx == null) {
            // 输出尚未建立（等首个 IDR），丢弃早到的少量音频
            audioQueue.clear();
            return;
        }
        AudioFrame f;
        while ((f = audioQueue.peek()) != null && f.outMs <= upToMs) {
            audioQueue.poll();
            writeAudio(f);
        }
    }

    private void writeAudio(AudioFrame f) {
        av_new_packet(audioPkt, f.data.length);
        audioPkt.data().put(f.data, 0, f.data.length);
        // 每段时间戳从 0 开始
        long relMs = f.outMs - segmentStartMs;
        audioPkt.pts(relMs * 48L);
        audioPkt.dts(relMs * 48L);
        audioPkt.duration(OPUS_FRAME_SAMPLES);
        audioPkt.stream_index(outAudio.index());
        audioPkt.flags(0);
        av_packet_rescale_ts(audioPkt, TB_OPUS, outAudio.time_base());
        int rc = av_interleaved_write_frame(outCtx, audioPkt);
        if (rc < 0) {
            log.warn("[{}] 写音频帧失败 rc={}", did, rc);
        }
        av_packet_unref(audioPkt);
    }

    private void openSegment(long startMs, byte[] config) throws IOException {
        String name = String.format("rec-%s-%03d-%s.mp4", did,
                segmentCounter.incrementAndGet(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        File file = new File(outDir, name);

        outCtx = new AVFormatContext(null);
        int rc = avformat_alloc_output_context2(outCtx, (AVOutputFormat) null, "mp4", file.getAbsolutePath());
        if (rc < 0) {
            throw new IOException("alloc mp4 output 失败 rc=" + rc);
        }

        outVideo = avformat_new_stream(outCtx, (org.bytedeco.ffmpeg.avcodec.AVCodec) null);
        // 手工设置视频参数（尺寸由 SPS 解析得到，参数集用手工 hvcC/avcC）。
        var vcp = outVideo.codecpar();
        vcp.codec_type(AVMEDIA_TYPE_VIDEO);
        vcp.codec_id(videoCodecId);
        vcp.codec_tag(0);
        int vw = pendingDim == null ? 0 : pendingDim[0];
        int vh = pendingDim == null ? 0 : pendingDim[1];
        vcp.width(vw);
        vcp.height(vh);
        vcp.format(AV_PIX_FMT_YUV420P);
        // extradata 用 av_malloc 分配，交 ffmpeg 所有权（free_context 时 av_free）
        videoConfigRef = new BytePointer(av_mallocz(config.length)).capacity(config.length);
        videoConfigRef.position(0).put(config, 0, config.length);
        vcp.extradata(videoConfigRef);
        vcp.extradata_size(config.length);
        outVideo.time_base(TB_MS);
        log.info("[{}] 视频段参数：{}x{} config={}B", did, vw, vh, config.length);

        outAudio = avformat_new_stream(outCtx, (org.bytedeco.ffmpeg.avcodec.AVCodec) null);
        var cp = outAudio.codecpar();
        cp.codec_type(AVMEDIA_TYPE_AUDIO);
        cp.codec_id(AV_CODEC_ID_OPUS);
        cp.format(AV_SAMPLE_FMT_FLTP);
        cp.sample_rate(OPUS_SAMPLE_RATE);
        cp.channels(audioChannels);
        cp.frame_size(OPUS_FRAME_SAMPLES); // 每包固定 1920 样本（40ms@48k）
        byte[] head = opusHead(audioChannels, OPUS_SAMPLE_RATE);
        // extradata 必须由 av_malloc 分配，交给 ffmpeg 所有权（free_context 时 av_free）；
        // 直接给 JavaCPP BytePointer 会用错误分配器释放导致 avformat_free_context 卡死/堆损坏。
        opusExtradataRef = new BytePointer(av_mallocz(head.length)).capacity(head.length);
        opusExtradataRef.position(0).put(head, 0, head.length);
        cp.extradata(opusExtradataRef);
        cp.extradata_size(head.length);
        outAudio.time_base(TB_OPUS);

        outPb = new AVIOContext(null);
        if (avio_open(outPb, file.getAbsolutePath(), AVIO_FLAG_WRITE) < 0) {
            throw new IOException("avio_open 失败：" + file);
        }
        outCtx.pb(outPb);
        // 分片 MP4（fMP4 / CMAF）：
        //  empty_moov —— 把 moov（含 trak 与 hvcC/avcC、OpusHead）写到文件头，不依赖结尾 trailer；
        //  frag_keyframe —— 每个关键帧（IDR）输出一个 moof+mdat 分片，写完即落盘可读；
        //  default_base_moof —— 分片内偏移以 moof 为基准，兼容性最好。
        // 效果：① 当前正在写入的段也能随时打开播放“已录部分”（读到当前文件末尾即停）；
        // ② 进程崩溃 / 断电时已写分片仍可解码，不丢整段（普通 MP4 未写 trailer 则整段报废）。
        AVDictionary opt = new AVDictionary();
        av_dict_set(opt, "movflags", "frag_keyframe+empty_moov+default_base_moof", 0);
        int wh = avformat_write_header(outCtx, opt);
        av_dict_free(opt);
        if (wh < 0) {
            throw new IOException("write_header(fMP4) 失败：" + file);
        }
        segmentStartMs = startMs;
        lastVideoRelMs = -1;
        currentSegmentName = name;
        log.info("[{}] 开始录像分段：{}（{}，起始 t={}ms）", did, name,
                videoCodecId == AV_CODEC_ID_HEVC ? "H.265" : "H.264", startMs);
    }

    private void closeSegment() {
        if (outCtx == null) {
            return;
        }
        // 先清除“录制中”标记：从此刻起该文件已是完成段（trailer 写入期间也不应再标记为录制中）。
        currentSegmentName = null;
        try {
            int trc = av_write_trailer(outCtx);
            if (trc < 0) {
                log.warn("[{}] av_write_trailer 返回 rc={}", did, trc);
            }
        } catch (Throwable t) {
            log.warn("[{}] 写 trailer 异常：{}", did, t.toString());
        }
        try {
            avio_closep(outCtx.pb());
            // 置空 pb，避免随后 avformat_free_context 对同一 AVIOContext 双重释放
            outCtx.pb((AVIOContext) null);
        } catch (Throwable ignore) {
            // 关闭异常忽略
        }
        avformat_free_context(outCtx);
        outCtx = null;
        outVideo = null;
        outAudio = null;
        outPb = null;
    }

    /** 设备毫秒时间戳 → 连续输出毫秒；回退（重连）或 >1s 空洞时平移，保证时间轴连续。 */
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

    private record VideoFrame(long outMs, byte[] data) {
    }

    private record AudioFrame(long outMs, byte[] data) {
    }

    /**
     * 构造 OpusHead（identification header），channel mapping family 0（支持单/双声道）。
     * 布局："OpusHead"(8) + ver(1) + ch(1) + preskip(LE16) + rate(LE32) + gain(LE16) + mapping(1)。
     */
    static byte[] opusHead(int channels, int sampleRate) {
        byte[] b = new byte[19];
        System.arraycopy("OpusHead".getBytes(), 0, b, 0, 8);
        b[8] = 1;
        b[9] = (byte) channels;
        b[10] = 0;
        b[11] = 0;
        b[12] = (byte) (sampleRate & 0xFF);
        b[13] = (byte) ((sampleRate >> 8) & 0xFF);
        b[14] = (byte) ((sampleRate >> 16) & 0xFF);
        b[15] = (byte) ((sampleRate >> 24) & 0xFF);
        b[16] = 0;
        b[17] = 0;
        b[18] = 0;
        return b;
    }

    // ------------------------------------------------------------------
    // hvcC / avcC（解码器配置记录）手工构造
    // ------------------------------------------------------------------

    /** 按 Annex-B 起始码（00 00 00 01 / 00 00 01）切分，返回不含起始码的原始 NAL 列表。 */
    static java.util.List<byte[]> splitNals(byte[] b) {
        java.util.List<byte[]> nals = new java.util.ArrayList<>();
        int n = b.length;
        int i = 0;
        int start = -1;
        while (i < n) {
            int sc;
            if (i + 3 < n && b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1) {
                sc = 4;
            } else if (i + 2 < n && b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
                sc = 3;
            } else {
                i++;
                continue;
            }
            if (start >= 0) {
                nals.add(java.util.Arrays.copyOfRange(b, start, i));
            }
            start = i + sc;
            i += sc;
        }
        if (start >= 0 && start < n) {
            nals.add(java.util.Arrays.copyOfRange(b, start, n));
        }
        return nals;
    }

    /**
     * 从含 VPS/SPS/PPS 的 H.265 Annex-B 访问单元构造 HEVCDecoderConfigurationRecord（hvcC）。
     * 不含参数集时返回 null。profile_tier_level 直接取自 SPS（max_sub_layers=0 常见情形）。
     */
    static byte[] buildHevcConfigRecord(byte[] annexb) {
        byte[] vps = null, sps = null, pps = null;
        for (byte[] nal : splitNals(annexb)) {
            if (nal.length == 0) {
                continue;
            }
            int type = (nal[0] >> 1) & 0x3F;
            if (type == 32 && vps == null) {
                vps = nal;
            } else if (type == 33 && sps == null) {
                sps = nal;
            } else if (type == 34 && pps == null) {
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
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
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

    private static void writeHevcArray(java.io.ByteArrayOutputStream o, int nalType, byte[] nal) {
        o.write(0x80 | (nalType & 0x3F)); // array_completeness = 1
        o.write(0x00);
        o.write(0x01);                    // numNalus = 1
        o.write((nal.length >> 8) & 0xFF);
        o.write(nal.length & 0xFF);
        o.write(nal, 0, nal.length);
    }

    /** 从含 SPS/PPS 的 H.264 Annex-B 访问单元构造 AVCDecoderConfigurationRecord（avcC）。 */
    static byte[] buildAvcConfigRecord(byte[] annexb) {
        byte[] sps = null, pps = null;
        for (byte[] nal : splitNals(annexb)) {
            if (nal.length == 0) {
                continue;
            }
            int type = nal[0] & 0x1F;
            if (type == 7 && sps == null) {
                sps = nal;
            } else if (type == 8 && pps == null) {
                pps = nal;
            }
        }
        if (sps == null || pps == null || sps.length < 4) {
            return null;
        }
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
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
    static int[] parseHevcDimensionsFromAu(byte[] annexb) {
        for (byte[] nal : splitNals(annexb)) {
            if (nal.length > 0 && ((nal[0] >> 1) & 0x3F) == 33) {
                return parseHevcDimensions(nal);
            }
        }
        return null;
    }

    /** 解析 H.265 SPS NAL（含 2 字节 NAL 头）得到图像宽高；仅实现 max_sub_layers=0 的常见情形。 */
    static int[] parseHevcDimensions(byte[] spsNal) {
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
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(nal.length);
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
