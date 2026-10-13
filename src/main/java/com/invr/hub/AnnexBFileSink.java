package com.invr.hub;

import com.invr.miss.MissCommands;
import com.invr.miss.MissPacket;
import com.invr.miss.NalUnits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * 最简消费者：把视频 Annex-B 裸流追加写入单个文件，音频仅计数。
 *
 * <p>跨自动重连持续追加同一个文件——重连后设备会重新下发 VPS/SPS/PPS/IDR，
 * 因此拼接后的裸流仍可被 ffmpeg/VLC 正常解码。用于 M2 取流/长稳验证与调试。</p>
 */
public final class AnnexBFileSink implements StreamSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnnexBFileSink.class);

    private final String did;
    private final String outArg;

    private FileOutputStream out;
    private String filePath;
    private String codecName;

    private int currentGen;
    private int reconnects;
    private long videoPackets;
    private long idrPackets;
    private long videoBytes;
    private final Map<Integer, Long> audioPackets = new TreeMap<>();
    private long startMs = System.currentTimeMillis();
    private long lastPacketMs;

    public AnnexBFileSink(String did, String outArg) {
        this.did = did;
        this.outArg = outArg;
    }

    @Override
    public void onReset(int generation) {
        currentGen = generation;
        if (generation > 1) {
            reconnects++;
            log.info("[{}] 检测到上游重连（generation={}，累计重连 {} 次），裸流文件继续追加",
                    did, generation, reconnects);
        }
    }

    @Override
    public void onPacket(MissPacket pkt, int generation) {
        if (generation != currentGen) {
            return; // 旧代数残留包，丢弃
        }
        lastPacketMs = System.currentTimeMillis();
        if (pkt.isVideo()) {
            ensureOpen(pkt.isH265());
            try {
                out.write(pkt.payload());
            } catch (IOException e) {
                throw new RuntimeException("写裸流文件失败：" + e.getMessage(), e);
            }
            videoPackets++;
            videoBytes += pkt.payload().length;
            if (NalUnits.containsIdr(pkt.isH265(), pkt.payload())) {
                idrPackets++;
            }
        } else {
            audioPackets.merge(pkt.codecId(), 1L, Long::sum);
        }
    }

    private void ensureOpen(boolean h265) {
        if (out != null) {
            return;
        }
        try {
            String path = outArg != null ? outArg : "stream-" + did + (h265 ? ".h265" : ".h264");
            // 覆盖旧文件；同一进程内自动重连不重开句柄，天然在同一文件尾部持续追加
            out = new FileOutputStream(path, false);
            filePath = new File(path).getAbsolutePath();
            codecName = h265 ? "H.265" : "H.264";
            log.info("[{}] 视频编码 = {}，裸流（Annex-B）写入：{}", did, codecName, filePath);
        } catch (IOException e) {
            throw new RuntimeException("无法创建裸流文件：" + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        if (out != null) {
            out.flush();
            out.close();
            out = null;
        }
    }

    public String filePath() {
        return filePath;
    }

    public String codecName() {
        return codecName;
    }

    public int reconnects() {
        return reconnects;
    }

    public long videoPackets() {
        return videoPackets;
    }

    public long idrPackets() {
        return idrPackets;
    }

    public long videoBytes() {
        return videoBytes;
    }

    public Map<Integer, Long> audioPackets() {
        return audioPackets;
    }

    public long elapsedMs() {
        return (lastPacketMs == 0 ? System.currentTimeMillis() : lastPacketMs) - startMs;
    }
}
