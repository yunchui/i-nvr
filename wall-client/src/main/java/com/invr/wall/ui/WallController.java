package com.invr.wall.ui;

import com.invr.wall.board.BoardClient;
import com.invr.wall.board.BoardDto;
import com.invr.wall.board.BoardSyncer;
import com.invr.wall.board.ShareLayout;
import com.invr.wall.config.WallConfig;
import com.invr.wall.event.SseClient;
import com.invr.wall.media.RtspPlayer;
import com.invr.wall.media.PlayerPool;
import com.invr.wall.support.Clock;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 大屏总编排：看板渲染/reconcile、SSE 事件状态机、播放池调度、看门狗与失效兜底。
 *
 * <p>事件规则与 Web 端 share.js 严格同构：grid4/9 全屏覆盖（独立临时播放器，end 销毁）、
 * grid6 基于默认映射单次交换（只换 GridPane 约束，播放器实例不动）、每次 switch 先复原、
 * 本地按 expireAt 挂兜底计时器。所有 SSE/同步器回调均切 FX 线程执行。</p>
 */
public final class WallController {

    private static final Logger log = LoggerFactory.getLogger(WallController.class);
    private static final long WATCHDOG_INTERVAL_MS = 2_000L;

    /** FX 节点集合，由 WallApplication 装配后传入。 */
    public record WallViews(GridPane boardGrid, StackPane fullscreenLayer, Label notice,
                            Pane bootLayer, Label bootTitle, Label bootSub) {
    }

    private static final class EventState {
        String did;
        long expireAt;
        ScheduledFuture<?> timer;
        String swappedSlot;       // grid6：与 BIG 交换的槽位 key
        RtspPlayer fsPlayer;      // grid4/9：全屏临时播放器
        CellPane fsCell;
    }

    private final WallConfig config;
    private final OkHttpClient http;
    private final WallViews views;
    private final Clock clock = new Clock();
    private final PlayerPool pool = new PlayerPool();
    private final BoardClient boardClient;
    private final BoardSyncer syncer;
    private SseClient sse;
    private final ScheduledExecutorService scheduler;

    private final Map<String, CellPane> cells = new LinkedHashMap<>();
    private volatile BoardDto currentBoard;
    private EventState eventState;
    private boolean sseStarted;
    private boolean fataled;

    public WallController(WallConfig config, OkHttpClient http, WallViews views) {
        this.config = config;
        this.http = http;
        this.views = views;
        this.boardClient = new BoardClient(http, config);
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "wall-ctrl");
            t.setDaemon(true);
            return t;
        });
        this.syncer = new BoardSyncer(boardClient, new BoardSyncer.Listener() {
            @Override
            public void onBoard(BoardDto board) {
                Platform.runLater(() -> handleBoard(board));
            }

            @Override
            public void onGone() {
                Platform.runLater(() -> fatal("共享看板不可用、已过期或已停用"));
            }

            @Override
            public void onError(Exception e) {
                Platform.runLater(() -> showNotice("看板同步中断，保留当前画面，自动重试中…", true));
            }
        });
    }

    // ===================== 生命周期 =====================

    public void start() {
        views.bootTitle.setText("正在加载共享看板…");
        views.bootSub.setText(config.baseUrl + "/share/" + config.token);
        showLayer(true);
        syncer.start();
        scheduler.scheduleAtFixedRate(this::watchdogTick,
                WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        syncer.stop();
        if (sse != null) {
            sse.stop();
        }
        scheduler.shutdownNow();
        destroyFullscreen();
        pool.stopAll();
    }

    // ===================== 看板渲染与 reconcile =====================

    private void handleBoard(BoardDto board) {
        if (fataled) {
            return;
        }
        clock.calibrate(board.serverTime);
        BoardDto old = currentBoard;
        currentBoard = board;

        if (old == null || needsFullRerender(old, board)) {
            renderGrid(board);
        } else {
            reconcile(board);
        }
        if (!sseStarted) {
            startSse();
        }
        showLayer(false);
        hideNotice();
    }

    private boolean needsFullRerender(BoardDto old, BoardDto now) {
        if (!java.util.Objects.equals(old.layout, now.layout)
                || old.slots.size() != now.slots.size()) {
            return true;
        }
        for (int i = 0; i < now.slots.size(); i++) {
            if (!java.util.Objects.equals(old.slots.get(i).slot, now.slots.get(i).slot)) {
                return true;
            }
        }
        return false;
    }

    private void renderGrid(BoardDto board) {
        clearEvent();
        destroyFullscreen();
        cells.clear();
        views.boardGrid.getChildren().clear();
        views.boardGrid.getColumnConstraints().clear();
        views.boardGrid.getRowConstraints().clear();

        int tracks = ShareLayout.trackCount(board.layout);
        for (int i = 0; i < tracks; i++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(100.0 / tracks);
            views.boardGrid.getColumnConstraints().add(cc);
            javafx.scene.layout.RowConstraints rc = new javafx.scene.layout.RowConstraints();
            rc.setPercentHeight(100.0 / tracks);
            views.boardGrid.getRowConstraints().add(rc);
        }

        for (BoardDto.Slot slot : board.slots) {
            CellPane cell = new CellPane(slot.slot, tagText(board.layout, slot.slot));
            cell.setCameraName(slot.cameraName);
            GridPane.setConstraints(cell, slot.colStart - 1, slot.rowStart - 1,
                    slot.colSpan, slot.rowSpan);
            views.boardGrid.getChildren().add(cell);
            cells.put(slot.slot, cell);
            bindSlot(board, slot, cell);
        }
        // 全量渲染后回收不在新板中的旧播放器
        pool.retainOnly(activeDids(board));
        log.info("看板已渲染：layout={} 槽位={}", board.layout, board.slots.size());
    }

    private void reconcile(BoardDto board) {
        for (BoardDto.Slot slot : board.slots) {
            CellPane cell = cells.get(slot.slot);
            if (cell == null) {
                continue;
            }
            String oldDid = cell.did();
            cell.setCameraName(slot.cameraName);

            if (java.util.Objects.equals(oldDid, slot.did)) {
                // did 未变：只处理在线状态翻转
                if (slot.did == null) {
                    continue;
                }
                boolean wasOffline = cell.player() == null;
                if (!slot.online && !wasOffline) {
                    pool.markOffline(slot.did);
                    cell.showOffline(slot.did);
                } else if (slot.online && wasOffline) {
                    bindSlot(board, slot, cell);
                }
            } else {
                // did 变化：事件进行中跳过，end 后下个周期再处理（对齐 share.js）
                if (eventState != null) {
                    log.info("事件进行中，跳过槽位 {} 的 did 替换", slot.slot);
                    continue;
                }
                bindSlot(board, slot, cell);
            }
        }
        pool.retainOnly(activeDids(board));
    }

    private void bindSlot(BoardDto board, BoardDto.Slot slot, CellPane cell) {
        if (slot.did == null) {
            cell.showEmpty();
            return;
        }
        if (!slot.online) {
            cell.showOffline(slot.did);
            return;
        }
        String url = boardClient.rtspUrlFor(board, slot.did);
        RtspPlayer player = pool.acquire(slot.did, url, cell.playerListener());
        cell.bindPlayer(slot.did, player);
    }

    private Set<String> activeDids(BoardDto board) {
        Set<String> dids = new HashSet<>();
        for (BoardDto.Slot s : board.slots) {
            if (s.did != null) {
                dids.add(s.did);
            }
        }
        return dids;
    }

    private static String tagText(String layout, String slotKey) {
        if (ShareLayout.GRID6.equals(layout) && "BIG".equals(slotKey)) {
            return "主";
        }
        return slotKey;
    }

    // ===================== SSE 事件 =====================

    private void startSse() {
        sseStarted = true;
        sse = new SseClient(http, config.eventsUrl(), new SseClient.Listener() {
            @Override
            public void onConnected() {
                Platform.runLater(() -> hideNotice());
            }

            @Override
            public void onDisconnected() {
                Platform.runLater(() -> showNotice("事件连接中断，正在自动重连…", true));
            }

            @Override
            public void onSnapshot(boolean active, String did, Long expireAt) {
                Platform.runLater(() -> {
                    if (active && did != null && expireAt != null) {
                        applySwitch(did, expireAt);
                    } else {
                        clearEvent();
                    }
                    hideNotice();
                });
            }

            @Override
            public void onSwitch(String did, long expireAt) {
                Platform.runLater(() -> applySwitch(did, expireAt));
            }

            @Override
            public void onEnd() {
                Platform.runLater(WallController.this::clearEvent);
            }

            @Override
            public void onGone() {
                Platform.runLater(() -> fatal("共享看板不可用、已过期或已停用"));
            }
        });
        sse.start();
    }

    /** 应用事件：先复原旧事件保证基于默认映射，再按布局响应。 */
    private void applySwitch(String did, long expireAt) {
        if (currentBoard == null || did == null) {
            return;
        }
        clearEvent();
        EventState es = new EventState();
        es.did = did;
        es.expireAt = expireAt;

        if (ShareLayout.GRID6.equals(currentBoard.layout)) {
            es.swappedSlot = swapForGrid6(did);
        } else {
            showFullscreen(did);
            es.fsPlayer = fullscreenPlayer;
            es.fsCell = fullscreenCell;
        }

        long delayMs = Math.max(1000L, clock.millisUntil(expireAt));
        es.timer = scheduler.schedule(() -> Platform.runLater(this::clearEvent),
                delayMs, TimeUnit.MILLISECONDS);
        eventState = es;
        log.info("事件切换 did={} 持续 {}ms", did, delayMs);
    }

    /** grid6：事件 did 所在槽位与 BIG 互换约束；找不到或本就在 BIG 返回 null。 */
    private String swapForGrid6(String did) {
        String targetKey = null;
        for (Map.Entry<String, CellPane> e : cells.entrySet()) {
            if (did.equals(e.getValue().did())) {
                targetKey = e.getKey();
                break;
            }
        }
        if (targetKey == null || "BIG".equals(targetKey)) {
            return null;
        }
        CellPane big = cells.get("BIG");
        CellPane target = cells.get(targetKey);
        if (big != null && target != null) {
            swapConstraints(big, target);
        }
        return targetKey;
    }

    private void swapConstraints(CellPane a, CellPane b) {
        Integer ac = GridPane.getColumnIndex(a);
        Integer ar = GridPane.getRowIndex(a);
        Integer acs = GridPane.getColumnSpan(a);
        Integer ars = GridPane.getRowSpan(a);

        GridPane.setConstraints(a, GridPane.getColumnIndex(b), GridPane.getRowIndex(b),
                GridPane.getColumnSpan(b), GridPane.getRowSpan(b));
        GridPane.setConstraints(b, ac, ar, acs, ars);
    }

    // ===================== grid4/9 全屏覆盖层 =====================

    private RtspPlayer fullscreenPlayer;
    private CellPane fullscreenCell;

    private void showFullscreen(String did) {
        destroyFullscreen();
        BoardDto board = currentBoard;
        String cameraName = did;
        for (BoardDto.Slot s : board.slots) {
            if (did.equals(s.did)) {
                cameraName = s.cameraName;
                break;
            }
        }
        CellPane cell = new CellPane("EVENT", "事件");
        cell.setEventTag(true);
        cell.setCameraName(cameraName);
        cell.prefWidthProperty().bind(views.fullscreenLayer.widthProperty());
        cell.prefHeightProperty().bind(views.fullscreenLayer.heightProperty());

        String url = boardClient.rtspUrlFor(board, did);
        RtspPlayer player = new RtspPlayer(did, url, cell.playerListener());
        cell.bindPlayer(did, player);
        player.start();

        fullscreenCell = cell;
        fullscreenPlayer = player;
        views.fullscreenLayer.getChildren().add(cell);
        views.fullscreenLayer.setVisible(true);
        views.fullscreenLayer.setManaged(true);
    }

    private void destroyFullscreen() {
        if (fullscreenPlayer != null) {
            fullscreenPlayer.stop();
            fullscreenPlayer = null;
        }
        fullscreenCell = null;
        views.fullscreenLayer.getChildren().clear();
        views.fullscreenLayer.setVisible(false);
        views.fullscreenLayer.setManaged(false);
    }

    /** 事件复原：grid6 约束归位 / 销毁全屏，并取消兜底计时器。 */
    private void clearEvent() {
        EventState es = eventState;
        if (es == null) {
            return;
        }
        if (es.timer != null) {
            es.timer.cancel(false);
        }
        if (es.swappedSlot != null) {
            CellPane big = cells.get("BIG");
            CellPane swapped = cells.get(es.swappedSlot);
            if (big != null && swapped != null) {
                swapConstraints(big, swapped);
            }
        }
        destroyFullscreen();
        eventState = null;
        log.info("事件复原");
    }

    // ===================== 看门狗 / 失效 / 状态条 =====================

    /** 快捷键手动刷新：重连池内全部在播路与事件全屏临时路，并给出即时 loading 与 3s 顶部提示。 */
    public void refreshConnections() {
        if (fataled) {
            return;
        }
        log.info("手动刷新全部 RTSP 连接");
        pool.refreshAll();
        if (fullscreenPlayer != null) {
            fullscreenPlayer.reconnect();
        }
        // 不等 worker 状态翻转，先给所有在播格子即时遮罩反馈，出画后由播放器状态回调覆盖
        for (CellPane cell : cells.values()) {
            if (cell.player() != null) {
                cell.flashRefreshing();
            }
        }
        if (fullscreenCell != null) {
            fullscreenCell.flashRefreshing();
        }
        showNotice("正在刷新全部画面连接…", false);
        scheduler.schedule(() -> Platform.runLater(this::hideNotice), 3, TimeUnit.SECONDS);
    }

    private void watchdogTick() {
        long now = System.currentTimeMillis();
        for (RtspPlayer p : poolAllPlayers()) {
            try {
                p.tickWatchdog(now);
            } catch (Exception e) {
                log.warn("看门狗检查异常：{}", e.toString());
            }
        }
    }

    private java.util.List<RtspPlayer> poolAllPlayers() {
        java.util.List<RtspPlayer> all = new java.util.ArrayList<>();
        for (CellPane cell : cells.values()) {
            Optional.ofNullable(cell.player()).ifPresent(all::add);
        }
        if (fullscreenPlayer != null) {
            all.add(fullscreenPlayer);
        }
        return all;
    }

    private void fatal(String message) {
        if (fataled) {
            return;
        }
        fataled = true;
        log.warn("进入致命失效态：{}", message);
        syncer.stop();
        if (sse != null) {
            sse.stop();
        }
        destroyFullscreen();
        pool.stopAll();
        views.bootTitle.setText("共享看板不可用");
        views.bootSub.setText(message);
        views.boardGrid.setVisible(false);
        views.boardGrid.setManaged(false);
        views.fullscreenLayer.setVisible(false);
        views.fullscreenLayer.setManaged(false);
        views.bootLayer.setVisible(true);
        views.bootLayer.setManaged(true);
        hideNotice();
    }

    private void showLayer(boolean boot) {
        views.bootLayer.setVisible(boot);
        views.bootLayer.setManaged(boot);
        views.boardGrid.setVisible(!boot);
        views.boardGrid.setManaged(!boot);
    }

    private void showNotice(String text, boolean danger) {
        views.notice.setText(text);
        views.notice.getStyleClass().remove("danger");
        if (danger) {
            views.notice.getStyleClass().add("danger");
        }
        views.notice.setVisible(true);
        views.notice.setManaged(true);
        // launchWall 后 boardGrid 被 toFront，提示条必须重新置顶才不会被网格盖住
        views.notice.toFront();
    }

    private void hideNotice() {
        views.notice.setVisible(false);
        views.notice.setManaged(false);
    }
}
