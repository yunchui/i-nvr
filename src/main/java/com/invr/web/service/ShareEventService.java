package com.invr.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invr.model.ShareBoard;
import com.invr.web.config.NvrProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 共享看板事件状态机与 SSE 推送。
 *
 * <p>每个看板维护至多一个活动事件（{@code eventDid + expireAt + generation}），Webhook 触发时
 * 写入/覆盖并广播 {@code switch}，到期（generation 匹配）后清除并广播 {@code end}；
 * 窗口内新事件覆盖 did、重置计时。SSE 建连先下发快照，支持展示端刷新/重连恢复；
 * 周期心跳维持长连接。</p>
 */
@Service
public class ShareEventService {

    /** Webhook 触发结果，供控制器映射 HTTP 状态码。 */
    public enum TriggerResult { OK, UNAUTHORIZED, BOARD_UNAVAILABLE, DID_NOT_IN_BOARD }

    private static final Logger log = LoggerFactory.getLogger(ShareEventService.class);

    private final ShareBoardService boards;
    private final long durationMillis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler;

    /** boardId -> 活动事件 */
    private final ConcurrentHashMap<String, ActiveEvent> activeEvents = new ConcurrentHashMap<>();
    /** boardId -> SSE 连接组 */
    private final ConcurrentHashMap<String, Set<SseEmitter>> emitterGroups = new ConcurrentHashMap<>();

    public ShareEventService(ShareBoardService boards, NvrProperties props) {
        this.boards = boards;
        NvrProperties.Share cfg = props.getShare();
        this.durationMillis = cfg.getEventDurationSec() * 1000L;
        long heartbeatSec = cfg.getSseHeartbeatSec();
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "share-event-timer");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::heartbeat, heartbeatSec, heartbeatSec, TimeUnit.SECONDS);
    }

    /* ===================== Webhook 触发 ===================== */

    public TriggerResult trigger(String secret, String did) {
        ShareBoard b = boards.resolveBySecret(secret);
        if (b == null) {
            return TriggerResult.UNAUTHORIZED;
        }
        if (!boards.isAvailable(b)) {
            return TriggerResult.BOARD_UNAVAILABLE;
        }
        if (did == null || did.trim().isEmpty() || !b.cameras.contains(did)) {
            return TriggerResult.DID_NOT_IN_BOARD;
        }

        ActiveEvent prev = activeEvents.get(b.id);
        long generation = (prev == null ? 0 : prev.generation) + 1;
        long expireAt = System.currentTimeMillis() + durationMillis;
        ActiveEvent ev = new ActiveEvent(did, expireAt, generation);
        activeEvents.put(b.id, ev);

        broadcast(b.id, switchMessage(ev));
        scheduler.schedule(() -> expire(b.id, generation), durationMillis, TimeUnit.MILLISECONDS);
        log.info("看板 {} 事件触发 did={} generation={}", b.id, did, generation);
        return TriggerResult.OK;
    }

    /** 到期任务：仅当 generation 仍为最新时清除，避免被覆盖后的旧任务误清除。 */
    private void expire(String boardId, long generation) {
        ActiveEvent cur = activeEvents.get(boardId);
        if (cur != null && cur.generation == generation) {
            activeEvents.remove(boardId);
            broadcast(boardId, Map.of("type", "end"));
            log.info("看板 {} 事件结束 generation={}", boardId, generation);
        }
    }

    /* ===================== SSE 订阅 ===================== */

    /** 订阅事件；看板不可用返回 null（控制器据此拒绝）。 */
    public SseEmitter subscribe(String token) {
        ShareBoard b = boards.resolveByToken(token);
        if (b == null) {
            return null;
        }
        SseEmitter emitter = new SseEmitter(0L);
        Set<SseEmitter> group = emitterGroups.computeIfAbsent(b.id, k -> ConcurrentHashMap.newKeySet());
        group.add(emitter);
        emitter.onCompletion(() -> removeEmitter(b.id, emitter));
        emitter.onTimeout(() -> removeEmitter(b.id, emitter));
        emitter.onError(e -> removeEmitter(b.id, emitter));

        ActiveEvent ev = activeEvents.get(b.id);
        send(emitter, ev == null ? Map.of("type", "snapshot", "active", false) : snapshotMessage(ev));
        return emitter;
    }

    /** 看板生命周期回调：关闭全部连接并清除事件（删除/禁用/令牌轮换时调用）。 */
    @EventListener
    public synchronized void closeBoard(String boardId) {
        activeEvents.remove(boardId);
        Set<SseEmitter> group = emitterGroups.remove(boardId);
        if (group != null) {
            for (SseEmitter emitter : group) {
                try { emitter.complete(); } catch (Exception e) { /* 忽略 */ }
            }
        }
    }

    /* ===================== 广播与心跳 ===================== */

    private void broadcast(String boardId, Map<String, Object> message) {
        Set<SseEmitter> group = emitterGroups.get(boardId);
        if (group == null) {
            return;
        }
        for (SseEmitter emitter : group) {
            if (!send(emitter, message)) {
                removeEmitter(boardId, emitter);
            }
        }
    }

    private boolean send(SseEmitter emitter, Map<String, Object> message) {
        try {
            String json = mapper.writeValueAsString(message);
            emitter.send(SseEmitter.event().data(json, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void heartbeat() {
        for (Map.Entry<String, Set<SseEmitter>> entry : emitterGroups.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (IOException e) {
                    removeEmitter(entry.getKey(), emitter);
                }
            }
        }
    }

    private void removeEmitter(String boardId, SseEmitter emitter) {
        Set<SseEmitter> group = emitterGroups.get(boardId);
        if (group != null) {
            group.remove(emitter);
        }
    }

    /* ===================== 消息构造 ===================== */

    private Map<String, Object> switchMessage(ActiveEvent ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "switch");
        m.put("did", ev.did);
        m.put("expireAt", ev.expireAt);
        return m;
    }

    private Map<String, Object> snapshotMessage(ActiveEvent ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "snapshot");
        m.put("active", true);
        m.put("did", ev.did);
        m.put("expireAt", ev.expireAt);
        return m;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        for (Set<SseEmitter> group : emitterGroups.values()) {
            for (SseEmitter emitter : group) {
                try { emitter.complete(); } catch (Exception e) { /* 忽略 */ }
            }
        }
    }

    /** 活动事件记录。 */
    private static class ActiveEvent {
        final String did;
        final long expireAt;
        final long generation;

        ActiveEvent(String did, long expireAt, long generation) {
            this.did = did;
            this.expireAt = expireAt;
            this.generation = generation;
        }
    }
}
