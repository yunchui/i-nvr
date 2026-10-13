package com.invr.rtsp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个解析后的 RTSP 客户端请求（仅文本信令；interleaved 入站二进制由 decoder 直接丢弃）。
 */
public final class RtspRequest {

    private final String method;
    private final String uri;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public RtspRequest(String method, String uri) {
        this.method = method;
        this.uri = uri;
    }

    public String method() {
        return method;
    }

    public String uri() {
        return uri;
    }

    public void putHeader(String name, String value) {
        headers.put(name.toLowerCase(), value.trim());
    }

    public String header(String name) {
        return headers.get(name.toLowerCase());
    }

    public int cseq() {
        String c = header("c-seq");
        if (c == null) {
            return 0;
        }
        try {
            return Integer.parseInt(c.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public int contentLength() {
        String c = header("content-length");
        if (c == null) {
            return 0;
        }
        try {
            return Integer.parseInt(c.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
