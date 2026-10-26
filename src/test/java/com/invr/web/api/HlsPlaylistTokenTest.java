package com.invr.web.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsPlaylistTokenTest {

    @Test
    void injectsTokenIntoMapAndSegmentUris() {
        String m3u8 = """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:2
                #EXT-X-MEDIA-SEQUENCE:0
                #EXT-X-INDEPENDENT-SEGMENTS
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:2.000000,
                seg-0.m4s
                #EXTINF:2.000000,
                seg-1.m4s
                """;
        byte[] out = HlsController.appendTokenToPlaylist(m3u8.getBytes(StandardCharsets.UTF_8), "TOK123");
        String txt = new String(out, StandardCharsets.UTF_8);

        assertTrue(txt.contains("#EXT-X-MAP:URI=\"init.mp4?__invr_t=TOK123\""),
                "EXT-X-MAP 初始化段 URI 必须携带令牌");
        assertTrue(txt.contains("seg-0.m4s?__invr_t=TOK123"), "分片 URI 必须携带令牌");
        assertTrue(txt.contains("seg-1.m4s?__invr_t=TOK123"), "分片 URI 必须携带令牌");
        assertFalse(txt.contains("#EXTINF:2.000000,?"), "标签行不得被当作 URI 追加查询参数");
        assertFalse(txt.contains("#EXTM3U?"), "标签行不得被追加查询参数");
    }
}
