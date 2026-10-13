package com.invr.cloud;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录流程内使用的内存 CookieJar。
 *
 * <p>OkHttp 默认按域匹配回传 cookie；额外提供按名全局查找（finishAuth 后取
 * userId/serviceToken 等，重定向链可能跨子域）。
 */
public class MemoryCookieJar implements CookieJar {

    private final Map<String, List<Cookie>> store = new ConcurrentHashMap<>();

    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        store.computeIfAbsent(url.host(), k -> new ArrayList<>()).addAll(cookies);
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        long now = System.currentTimeMillis();
        List<Cookie> matched = new ArrayList<>();
        for (List<Cookie> cookies : store.values()) {
            for (Cookie cookie : cookies) {
                if (cookie.expiresAt() >= now && cookie.matches(url)) {
                    matched.add(cookie);
                }
            }
        }
        return matched;
    }

    /** 预置 cookie（如登录前的 deviceId）。 */
    public synchronized void setCookie(HttpUrl url, Cookie cookie) {
        store.computeIfAbsent(url.host(), k -> new ArrayList<>()).add(cookie);
    }

    /** 跨全部 host 按名取第一个未过期 cookie 值。 */
    public synchronized String cookieValue(String name) {
        long now = System.currentTimeMillis();
        for (List<Cookie> cookies : store.values()) {
            for (Cookie cookie : cookies) {
                if (cookie.name().equals(name) && cookie.expiresAt() >= now) {
                    return cookie.value();
                }
            }
        }
        return null;
    }

    public synchronized void clear() {
        store.clear();
    }
}
