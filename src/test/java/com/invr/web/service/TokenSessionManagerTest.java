package com.invr.web.service;

import com.invr.web.service.TokenSessionManager.TokenSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenSessionManagerTest {

    @SuppressWarnings("unchecked")
    private Map<String, TokenSession> sessions(TokenSessionManager m) throws Exception {
        Field f = TokenSessionManager.class.getDeclaredField("sessions");
        f.setAccessible(true);
        return (Map<String, TokenSession>) f.get(m);
    }

    @Test
    void createIssuesUniqueUrlSafeTokenAndResolves() {
        TokenSessionManager m = new TokenSessionManager();
        TokenSession a = m.create();
        TokenSession b = m.create();
        assertNotNull(a.getToken());
        assertNotEquals(a.getToken(), b.getToken());
        // 32 字节经 Base64URL 无 padding 编码 = 43 字符
        assertEquals(43, a.getToken().length());
        assertFalse(a.getToken().contains("+"));
        assertFalse(a.getToken().contains("/"));
        assertFalse(a.getToken().contains("="));
        assertSame(a, m.resolve(a.getToken()));
        assertNull(m.resolve("not-a-token"));
        assertNull(m.resolve(null));
        assertNull(m.resolve(""));
    }

    @Test
    void expiredSessionIsRejectedAndRemoved() throws Exception {
        TokenSessionManager m = new TokenSessionManager();
        TokenSession s = m.create();
        Field last = TokenSession.class.getDeclaredField("lastAccessMs");
        last.setAccessible(true);
        last.setLong(s, System.currentTimeMillis() - 31 * 60_000L);
        assertNull(m.resolve(s.getToken()), "超过 30 分钟未活动的会话应判过期");
        assertFalse(sessions(m).containsKey(s.getToken()));
    }

    @Test
    void sessionCountIsBounded() throws Exception {
        TokenSessionManager m = new TokenSessionManager();
        for (int i = 0; i < 20_005; i++) {
            m.create();
        }
        assertTrue(sessions(m).size() <= 20_000, "会话数不得超过上限");
    }

    @Test
    void unlockFlagRoundTrips() {
        TokenSessionManager m = new TokenSessionManager();
        TokenSession s = m.create();
        assertFalse(s.isUnlocked());
        s.setUnlocked(true);
        assertTrue(m.resolve(s.getToken()).isUnlocked());
        s.setUnlocked(false);
        assertFalse(m.resolve(s.getToken()).isUnlocked());
    }
}
