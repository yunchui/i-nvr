package com.invr.wall.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网格常驻播放器池：按 did 复用 {@link RtspPlayer}。
 *
 * <p>同一 did 在 grid6 交换槽位时播放器实例不动（仅 UI 约束互换）；reconcile 发现某 did
 * 已不在看板槽位中时停止并移除。事件全屏覆盖层的临时播放器不进池，由 UI 层自行管理生命周期。</p>
 */
public final class PlayerPool {

    private static final Logger log = LoggerFactory.getLogger(PlayerPool.class);

    private final Map<String, RtspPlayer> players = new ConcurrentHashMap<>();

    /**
     * 获取或创建一路播放器并启动。播放器保留摄像头原始分辨率，尺寸与槽位解耦；已存在时直接复用。
     */
    public RtspPlayer acquire(String did, String rtspUrl, RtspPlayer.Listener listener) {
        RtspPlayer existing = players.get(did);
        if (existing != null) {
            existing.goOnline();
            return existing;
        }
        RtspPlayer player = new RtspPlayer(did, rtspUrl, listener);
        RtspPlayer old = players.putIfAbsent(did, player);
        if (old != null) {
            player.stop();
            old.goOnline();
            return old;
        }
        player.start();
        return player;
    }

    /** 仅切换离线态，不销毁实例（上线后无需重新建连对象）。 */
    public void markOffline(String did) {
        RtspPlayer p = players.get(did);
        if (p != null) {
            p.goOffline();
        }
    }

    /** reconcile 后保留集合内 did，其余播放器停止并释放。 */
    public void retainOnly(Set<String> retainedDids) {
        for (Map.Entry<String, RtspPlayer> e : players.entrySet()) {
            if (!retainedDids.contains(e.getKey())) {
                RtspPlayer removed = players.remove(e.getKey());
                if (removed != null) {
                    log.info("看板不再包含 did={}，释放播放器", e.getKey());
                    removed.stop();
                }
            }
        }
    }

    public RtspPlayer find(String did) {
        return players.get(did);
    }

    /** 手动刷新：全部在播播放器立即重连（离线实例内部自行跳过）。 */
    public void refreshAll() {
        players.forEach((did, p) -> p.reconnect());
    }

    public void stopAll() {
        players.forEach((did, p) -> p.stop());
        players.clear();
    }

    public int size() {
        return players.size();
    }
}
