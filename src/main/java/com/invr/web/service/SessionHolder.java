package com.invr.web.service;

import com.invr.cloud.LoginSession;
import com.invr.cloud.MiCloudClientImpl;
import com.invr.store.SessionStore;
import com.invr.web.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 当前米家登录会话的进程内持有者（Web 单例）。
 *
 * <p>所有下游（RTSP / HLS / 录像 / 设备发现）共享同一个 {@link AtomicReference}，
 * 云端续期后全局即时生效；会话同时加密落盘，重启可免密恢复。</p>
 */
@Component
public class SessionHolder {

    private static final Logger log = LoggerFactory.getLogger(SessionHolder.class);

    private final SessionStore store;
    private final AtomicReference<LoginSession> ref = new AtomicReference<>();

    public SessionHolder(SessionStore store) {
        this.store = store;
        store.load().ifPresent(s -> {
            ref.set(s);
            log.info("已从本地加密存储恢复米家会话 userId={} region={}", s.userId(), s.region());
        });
    }

    public AtomicReference<LoginSession> ref() {
        return ref;
    }

    public Optional<LoginSession> get() {
        return Optional.ofNullable(ref.get());
    }

    public LoginSession require() {
        LoginSession s = ref.get();
        if (s == null) {
            throw ApiException.unauthorized("尚未登录米家账号");
        }
        return s;
    }

    public boolean isLoggedIn() {
        return ref.get() != null;
    }

    /** 登录成功后调用：更新内存并加密落盘。 */
    public void accept(LoginSession session) {
        ref.set(session);
        store.save(session);
    }

    /** 用 passToken 续期会话（401 时），更新内存与落盘。 */
    public LoginSession renew() {
        LoginSession old = require();
        LoginSession renewed = new MiCloudClientImpl()
                .loginWithToken(old.userId(), old.passToken(), old.region());
        accept(renewed);
        log.info("米家会话已用 passToken 续期");
        return renewed;
    }

    public void clear() {
        ref.set(null);
        store.clear();
    }
}
