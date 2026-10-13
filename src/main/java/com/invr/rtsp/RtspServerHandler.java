package com.invr.rtsp;

import com.invr.hub.CameraConnector;
import com.invr.hub.StreamHub;
import com.invr.model.CameraDevice;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 单条 RTSP 客户端连接的信令状态机（每连接一个实例，非线程共享）。
 * 支持 OPTIONS/DESCRIBE/SETUP/PLAY/TEARDOWN，传输仅 RTP-over-TCP interleaved。
 */
public final class RtspServerHandler extends SimpleChannelInboundHandler<RtspRequest> {

    private static final Logger log = LoggerFactory.getLogger(RtspServerHandler.class);

    private final StreamHub hub;
    private final CameraConnector connector;

    private String did;
    private RtspStreamSink sink;
    private String sessionId;
    private boolean videoSetup;
    private boolean audioSetup;
    private boolean subscribed;

    public RtspServerHandler(StreamHub hub, CameraConnector connector) {
        this.hub = hub;
        this.connector = connector;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RtspRequest req) {
        try {
            switch (req.method().toUpperCase()) {
                case "OPTIONS" -> options(ctx, req);
                case "DESCRIBE" -> describe(ctx, req);
                case "SETUP" -> setup(ctx, req);
                case "PLAY" -> play(ctx, req);
                case "PAUSE" -> simpleOk(ctx, req);
                case "GET_PARAMETER", "SET_PARAMETER" -> simpleOk(ctx, req);
                case "TEARDOWN" -> {
                    simpleOk(ctx, req);
                    cleanup();
                    ctx.close();
                }
                default -> sendStatus(ctx, req, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            log.error("RTSP 处理 {} 异常：{}", req.method(), e.toString(), e);
            sendStatus(ctx, req, 500, "Internal Server Error");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cleanup();
    }

    private void options(ChannelHandlerContext ctx, RtspRequest req) {
        StringBuilder sb = baseResponse(req, 200, "OK");
        sb.append("Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER\r\n");
        sb.append("\r\n");
        send(ctx, sb);
    }

    private void describe(ChannelHandlerContext ctx, RtspRequest req) throws Exception {
        String pathDid = firstPathSegment(req.uri());
        if (pathDid == null) {
            sendStatus(ctx, req, 400, "Bad Request");
            return;
        }
        CameraDevice device = findCamera(pathDid);
        if (device == null) {
            sendStatus(ctx, req, 404, "Stream Not Found");
            return;
        }
        this.did = pathDid;
        ensureSink(ctx, device);

        // 等待参数集（GOP 回放通常很快；冷启动等到下一个 IDR，约数秒）
        boolean got = sink.awaitConfig(4_000);
        String host = req.header("host") != null ? req.header("host") : "127.0.0.1:8554";
        String sdp = buildSdp(host, did);
        byte[] body = sdp.getBytes(StandardCharsets.UTF_8);

        StringBuilder sb = baseResponse(req, 200, "OK");
        sb.append("Content-Base: rtsp://").append(host).append('/').append(did).append("/\r\n");
        sb.append("Content-Type: application/sdp\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("\r\n");
        send(ctx, sb, body);
        log.info("[{}] RTSP DESCRIBE 完成（参数集{}），SDP {} 字节", did, got ? "就绪" : "超时(流内仍会下发)", body.length);
    }

    private void setup(ChannelHandlerContext ctx, RtspRequest req) {
        if (sink == null) {
            String pathDid = firstPathSegment(req.uri());
            CameraDevice device = pathDid == null ? null : findCamera(pathDid);
            if (device == null) {
                sendStatus(ctx, req, 404, "Stream Not Found");
                return;
            }
            this.did = pathDid;
            try {
                ensureSink(ctx, device);
            } catch (Exception e) {
                sendStatus(ctx, req, 500, "Internal Server Error");
                return;
            }
        }
        if (sessionId == null) {
            sessionId = Integer.toHexString(System.identityHashCode(this) & 0x7FFFFFFF);
        }
        String lower = req.uri().toLowerCase();
        boolean isAudio = lower.contains("track1") || lower.contains("audio");
        String interleaved = isAudio ? "2-3" : "0-1";
        if (isAudio) {
            audioSetup = true;
        } else {
            videoSetup = true;
        }

        StringBuilder sb = baseResponse(req, 200, "OK");
        sb.append("Session: ").append(sessionId).append(";timeout=60\r\n");
        sb.append("Transport: RTP/AVP/TCP;unicast;interleaved=").append(interleaved).append("\r\n");
        sb.append("\r\n");
        send(ctx, sb);
        log.info("[{}] RTSP SETUP {} track（interleaved={}）", did, isAudio ? "音频" : "视频", interleaved);
    }

    private void play(ChannelHandlerContext ctx, RtspRequest req) {
        if (sink == null) {
            sendStatus(ctx, req, 454, "Session Not Found");
            return;
        }
        StringBuilder sb = baseResponse(req, 200, "OK");
        sb.append("Session: ").append(sessionId).append(";timeout=60\r\n");
        sb.append("Range: npt=0.000-\r\n");
        sb.append("\r\n");
        send(ctx, sb);
        sink.startPlay();
        log.info("[{}] RTSP PLAY 开始（video={}, audio={}）", did, videoSetup, audioSetup);
    }

    private void simpleOk(ChannelHandlerContext ctx, RtspRequest req) {
        StringBuilder sb = baseResponse(req, 200, "OK");
        if (sessionId != null) {
            sb.append("Session: ").append(sessionId).append(";timeout=60\r\n");
        }
        sb.append("Content-Length: 0\r\n");
        sb.append("\r\n");
        send(ctx, sb);
    }

    private void sendStatus(ChannelHandlerContext ctx, RtspRequest req, int code, String reason) {
        StringBuilder sb = baseResponse(req, code, reason);
        sb.append("Content-Length: 0\r\n\r\n");
        send(ctx, sb);
    }

    private void ensureSink(ChannelHandlerContext ctx, CameraDevice device) throws Exception {
        if (sink != null) {
            return;
        }
        sink = new RtspStreamSink(did, ctx.channel(), 0, 2);
        hub.subscribe(did, device.model(), connector.openerFor(device), sink);
        subscribed = true;
    }

    private synchronized void cleanup() {
        if (subscribed && sink != null && did != null) {
            hub.unsubscribe(did, sink);
            subscribed = false;
            log.info("[{}] RTSP 连接关闭，已退订（视频包={} 音频包={} RTP包={}）",
                    did, sink.videoPackets(), sink.audioPackets(), sink.rtpPackets());
        }
        sink = null;
    }

    private CameraDevice findCamera(String pathDid) {
        return connector.findDevice(pathDid);
    }

    private String buildSdp(String host, String streamDid) {
        StringBuilder s = new StringBuilder();
        long now = System.currentTimeMillis() / 1000;
        s.append("v=0\r\n");
        s.append("o=- ").append(now).append(' ').append(now).append(" IN IP4 0.0.0.0\r\n");
        s.append("s=invr\r\n");
        s.append("c=IN IP4 0.0.0.0\r\n");
        s.append("t=0 0\r\n");
        s.append("a=control:*\r\n");
        s.append("a=range:npt=0-\r\n");

        // 视频
        s.append("m=video 0 RTP/AVP ").append(RtspStreamSink.PT_VIDEO).append("\r\n");
        s.append("a=rtpmap:").append(RtspStreamSink.PT_VIDEO).append(' ')
                .append(sink != null && sink.isH265() ? "H265" : "H264").append("/90000\r\n");
        if (sink != null && sink.configReady()) {
            if (sink.isH265()) {
                s.append("a=fmtp:").append(RtspStreamSink.PT_VIDEO)
                        .append(" sprop-vps=").append(b64(sink.vps()))
                        .append("; sprop-sps=").append(b64(sink.sps()))
                        .append("; sprop-pps=").append(b64(sink.pps())).append("\r\n");
            } else {
                s.append("a=fmtp:").append(RtspStreamSink.PT_VIDEO)
                        .append(" packetization-mode=1; sprop-parameter-sets=")
                        .append(b64(sink.sps())).append(',').append(b64(sink.pps())).append("\r\n");
            }
        }
        s.append("a=control:track0\r\n");

        // 音频（Opus 48k/2）
        s.append("m=audio 0 RTP/AVP ").append(RtspStreamSink.PT_OPUS).append("\r\n");
        s.append("a=rtpmap:").append(RtspStreamSink.PT_OPUS).append(" OPUS/48000/2\r\n");
        s.append("a=fmtp:").append(RtspStreamSink.PT_OPUS)
                .append(" minptime=20;useinbandfec=1;sprop-stereo=1\r\n");
        s.append("a=control:track1\r\n");
        return s.toString();
    }

    private static String b64(byte[] data) {
        return data == null ? "" : Base64.getEncoder().encodeToString(data);
    }

    private static String firstPathSegment(String uri) {
        try {
            String path = URI.create(uri).getPath();
            if (path == null) {
                return null;
            }
            for (String seg : path.split("/")) {
                if (!seg.isEmpty()) {
                    return seg;
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    private StringBuilder baseResponse(RtspRequest req, int code, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("RTSP/1.0 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("CSeq: ").append(req.cseq()).append("\r\n");
        return sb;
    }

    private void send(ChannelHandlerContext ctx, StringBuilder text) {
        send(ctx, text, null);
    }

    private void send(ChannelHandlerContext ctx, StringBuilder text, byte[] body) {
        ByteBuf buf = ctx.alloc().buffer();
        buf.writeCharSequence(text.toString(), StandardCharsets.US_ASCII);
        if (body != null) {
            buf.writeBytes(body);
        }
        ctx.writeAndFlush(buf);
    }
}
