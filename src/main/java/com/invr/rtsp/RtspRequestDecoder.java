package com.invr.rtsp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RTSP-over-TCP 入站解码器：同一连接上区分
 * <ol>
 *   <li>RTSP 文本请求（方法首字母开头，以 CRLFCRLF 结束，可能带 Content-Length body）；</li>
 *   <li>RTP/RTCP interleaved 二进制（0x24 '$' + channel + BE16 len + data）——播放器上行的 RTCP，
 *       本服务不依赖，读出后丢弃。</li>
 * </ol>
 */
public final class RtspRequestDecoder extends ByteToMessageDecoder {

    private static final int MAX_HEADER_BYTES = 64 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() > 0) {
            in.markReaderIndex();
            int first = in.readByte() & 0xFF;

            if (first == 0x24) {
                // interleaved 二进制帧
                if (in.readableBytes() < 3) {
                    in.resetReaderIndex();
                    return;
                }
                in.readByte(); // channel
                int len = in.readUnsignedShort();
                if (in.readableBytes() < len) {
                    in.resetReaderIndex();
                    return;
                }
                in.skipBytes(len); // 丢弃上行 RTCP/RTP
                continue;
            }

            // 文本请求：回退首字节，查找头部结束标记
            in.resetReaderIndex();
            int headerStart = in.readerIndex();
            int end = indexOfDoubleCrlf(in);
            if (end < 0) {
                if (in.readableBytes() > MAX_HEADER_BYTES) {
                    ctx.close();
                }
                return;
            }
            int headerLen = (end - headerStart) + 4;
            ByteBuf headerBuf = in.readSlice(headerLen);
            String headerText = headerBuf.toString(StandardCharsets.US_ASCII);

            RtspRequest req = parse(headerText);
            int bodyLen = req.contentLength();
            if (bodyLen > 0) {
                if (in.readableBytes() < bodyLen) {
                    // body 未到齐，整体回退等待
                    in.readerIndex(headerStart);
                    return;
                }
                in.skipBytes(bodyLen); // 请求 body 本服务不使用
            }
            out.add(req);
        }
    }

    private static int indexOfDoubleCrlf(ByteBuf buf) {
        int n = buf.writerIndex();
        for (int i = buf.readerIndex(); i + 3 < n; i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n'
                    && buf.getByte(i + 2) == '\r' && buf.getByte(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static RtspRequest parse(String text) {
        String[] lines = text.split("\r\n");
        String[] requestLine = lines[0].split(" ", 3);
        String method = requestLine.length > 0 ? requestLine[0] : "";
        String uri = requestLine.length > 1 ? requestLine[1] : "";
        RtspRequest req = new RtspRequest(method, uri);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                req.putHeader(lines[i].substring(0, colon), lines[i].substring(colon + 1));
            }
        }
        return req;
    }
}
