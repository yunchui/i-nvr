/* iNvr 共享大屏页：多宫格渲染、多实例 HLS 播放、事件 SSE 与无人值守重连 */
(function () {
    'use strict';

    var share = window.__SHARE;
    var ROOT = share.root;
    var TOKEN = share.token;

    /* 轮询与兜底周期（秒）：内置默认值，拉取看板配置后用服务端生效值覆盖（设置页可配） */
    var CONFIG_POLL_SEC = 60;
    var PAGE_RELOAD_SEC = 3600;

    var board = null;
    /* slotKey -> 播放器记录 {slot, did, cell, video, hls, hlsUrl, online} */
    var players = new Map();
    /* 当前事件态 {did, expireAt, timer, swappedSlot} */
    var eventState = null;
    /* 全屏覆盖层播放器记录 */
    var fullscreenRec = null;
    var evtSource = null;

    function full(path) { return ROOT + path; }

    /* ===================== 配置拉取 ===================== */

    async function fetchBoard() {
        var res = await fetch(full('share/' + TOKEN + '/board'), { cache: 'no-store' });
        if (res.status === 410) {
            fatal('共享看板不可用、已过期或已停用');
            return null;
        }
        if (!res.ok) {
            throw new Error('HTTP ' + res.status);
        }
        return res.json();
    }

    /* ===================== 渲染 ===================== */

    function render(b) {
        var boardEl = document.getElementById('board');
        boardEl.className = 'board' + (b.layout === 'grid4' ? ' layout-grid4' : '');
        boardEl.innerHTML = '';
        b.slots.forEach(function (s) {
            var cell = document.createElement('div');
            cell.className = 'cell';
            cell.dataset.slot = s.slot;
            cell.style.gridColumn = s.colStart + ' / span ' + s.colSpan;
            cell.style.gridRow = s.rowStart + ' / span ' + s.rowSpan;
            boardEl.appendChild(cell);

            cell.appendChild(buildBar(s));
            startPlayer(cell, s);
        });
        boardEl.hidden = false;
        document.getElementById('boot').hidden = true;
    }

    function buildBar(s) {
        var bar = document.createElement('div');
        bar.className = 'cell-bar';
        var tag = document.createElement('span');
        tag.className = 'tag';
        tag.textContent = s.slot === 'BIG' ? '主' : s.slot;
        var name = document.createElement('span');
        name.textContent = s.cameraName;
        bar.appendChild(tag);
        bar.appendChild(name);
        return bar;
    }

    /* ===================== 网格播放器 ===================== */

    function startPlayer(cell, s) {
        /* 空置槽位：不创建播放器，只显示空置占位，并保留记录以便交换/轮询处理 */
        if (!s.did) {
            showPlaceholder(cell, '空置', 'empty');
            players.set(s.slot, { slot: s.slot, did: null, cell: cell, video: null,
                hls: null, hlsUrl: null, online: false, empty: true });
            return;
        }

        showPlaceholder(cell, '正在连接…', 'loading');

        var video = document.createElement('video');
        video.muted = true;
        video.autoplay = true;
        video.playsInline = true;
        cell.appendChild(video);

        var rec = { slot: s.slot, did: s.did, cell: cell, video: video, hls: null,
            hlsUrl: s.hlsUrl, online: s.online, empty: false };
        players.set(s.slot, rec);

        if (!s.online) {
            showPlaceholder(cell, '设备离线', 'offline');
            return;
        }
        attachHls(rec);
    }

    function attachHls(rec) {
        rec.hls = mountHls(rec.video, full(rec.hlsUrl), function () {
            scheduleRetry(rec);
        });
        if (rec.hls === undefined) {
            showPlaceholder(rec.cell, '当前浏览器不支持 HLS 播放', 'error');
            return;
        }
        rec.video.addEventListener('playing', function () {
            if (rec.online) { clearPlaceholder(rec.cell); }
        });
    }

    function destroyPlayer(rec) {
        if (rec.hls) {
            try { rec.hls.destroy(); } catch (e) { /* 忽略 */ }
        }
        if (rec.video) {
            rec.video.pause();
            rec.video.removeAttribute('src');
            rec.video.load();
        }
        players.delete(rec.slot);
    }

    function scheduleRetry(rec) {
        showPlaceholder(rec.cell, '取流失败，重试中…', 'error');
        setTimeout(function () {
            if (rec.hls) {
                try { rec.hls.destroy(); } catch (e) { /* 忽略 */ }
                rec.hls = null;
            }
            attachHls(rec);
        }, 5000);
    }

    /* ===================== 通用 HLS 挂载（网格/全屏复用） ===================== */

    function mountHls(video, src, onFatal) {
        if (video.canPlayType('application/vnd.apple.mpegurl')) {
            video.src = src;
            video.play().catch(function () {});
            return null;
        }
        if (window.Hls && Hls.isSupported()) {
            var hls = new Hls({ liveSyncDurationCount: 3, enableWorker: true });
            hls.loadSource(src);
            hls.attachMedia(video);
            hls.on(Hls.Events.MANIFEST_PARSED, function () {
                video.play().catch(function () {});
            });
            hls.on(Hls.Events.ERROR, function (evt, data) {
                if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
                    hls.startLoad();
                } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
                    try { hls.recoverMediaError(); }
                    catch (e) { if (onFatal) { onFatal(); } }
                }
            });
            return hls;
        }
        return undefined;
    }

    function findHlsUrl(did) {
        for (var i = 0; i < board.slots.length; i++) {
            if (board.slots[i].did === did) {
                return full(board.slots[i].hlsUrl);
            }
        }
        return null;
    }

    /* ===================== 占位 ===================== */

    function showPlaceholder(cell, text, kind) {
        clearPlaceholder(cell);
        var p = document.createElement('div');
        p.className = 'cell-placeholder ' + (kind || '');
        var ico = kind === 'offline' ? 'OFF' : kind === 'error' ? '!' : '…';
        p.innerHTML = '<span class="ico">' + ico + '</span><span>' + text + '</span>';
        cell.appendChild(p);
    }

    function clearPlaceholder(cell) {
        var p = cell.querySelector('.cell-placeholder');
        if (p) { p.remove(); }
    }

    /* ===================== 事件 SSE ===================== */

    function connectSSE() {
        if (!board.eventsUrl) {
            return;
        }
        evtSource = new EventSource(full(board.eventsUrl));
        evtSource.onmessage = function (e) {
            try {
                handleMessage(JSON.parse(e.data));
            } catch (err) { /* 忽略坏消息 */ }
        };
        evtSource.onopen = function () { hideNotice(); };
        evtSource.onerror = function () {
            showNotice('事件连接中断，正在自动重连…', 'info');
        };
    }

    function handleMessage(msg) {
        if (msg.type === 'snapshot') {
            if (msg.active) {
                applySwitch(msg.did, msg.expireAt);
            } else {
                clearEvent();
            }
            hideNotice();
        } else if (msg.type === 'switch') {
            applySwitch(msg.did, msg.expireAt);
        } else if (msg.type === 'end') {
            clearEvent();
        }
    }

    /* ===================== 事件切换（基于默认布局） ===================== */

    function applySwitch(did, expireAt) {
        /* 先还原旧事件，保证永远基于默认布局单次操作 */
        revertEvent();

        var swappedSlot = null;
        if (board.layout === 'grid6') {
            swappedSlot = swapForGrid6(did);
        } else {
            showFullscreen(did);
        }

        var delay = Math.max(1000, expireAt - Date.now());
        var timer = setTimeout(localEnd, delay);
        eventState = { did: did, expireAt: expireAt, timer: timer, swappedSlot: swappedSlot };
    }

    /* 还原当前事件：grid6 归位、其余销毁全屏，并清计时器 */
    function revertEvent() {
        if (!eventState) {
            return;
        }
        clearTimeout(eventState.timer);
        if (board.layout === 'grid6') {
            if (eventState.swappedSlot) {
                swapCells('BIG', eventState.swappedSlot);
            }
        } else {
            destroyFullscreen();
        }
        eventState = null;
    }

    function clearEvent() {
        revertEvent();
    }

    function localEnd() {
        clearEvent();
    }

    /* ===================== grid6 单次交换 ===================== */

    function swapForGrid6(did) {
        var targetSlot = null;
        players.forEach(function (rec, key) {
            if (rec.did === did) {
                targetSlot = key;
            }
        });
        if (targetSlot === null || targetSlot === 'BIG') {
            /* 找不到或已在大格：无需交换 */
            return null;
        }
        swapCells('BIG', targetSlot);
        return targetSlot;
    }

    function swapCells(keyA, keyB) {
        var recA = players.get(keyA);
        var recB = players.get(keyB);
        var cellA = recA.cell;
        var cellB = recB.cell;

        var nodesA = Array.prototype.slice.call(cellA.childNodes);
        var nodesB = Array.prototype.slice.call(cellB.childNodes);
        nodesA.forEach(function (n) { cellB.appendChild(n); });
        nodesB.forEach(function (n) { cellA.appendChild(n); });

        /* 播放器随 video 移动，更新容器引用 */
        recA.cell = cellB;
        recB.cell = cellA;
    }

    /* ===================== grid4/9 全屏覆盖层 ===================== */

    function showFullscreen(did) {
        var layer = document.getElementById('fullscreenLayer');
        layer.innerHTML = '';

        var video = document.createElement('video');
        video.muted = true;
        video.autoplay = true;
        video.playsInline = true;
        layer.appendChild(video);

        var bar = document.createElement('div');
        bar.className = 'cell-bar';
        var tag = document.createElement('span');
        tag.className = 'tag event';
        tag.textContent = '事件';
        var name = document.createElement('span');
        name.textContent = cameraName(did);
        bar.appendChild(tag);
        bar.appendChild(name);
        layer.appendChild(bar);

        var src = findHlsUrl(did);
        var hls = null;
        if (src) {
            hls = mountHls(video, src, null);
        }
        fullscreenRec = { did: did, video: video, hls: hls };
        layer.hidden = false;
    }

    function destroyFullscreen() {
        if (fullscreenRec && fullscreenRec.hls) {
            try { fullscreenRec.hls.destroy(); } catch (e) { /* 忽略 */ }
        }
        var layer = document.getElementById('fullscreenLayer');
        layer.hidden = true;
        layer.innerHTML = '';
        fullscreenRec = null;
    }

    function cameraName(did) {
        for (var i = 0; i < board.slots.length; i++) {
            if (board.slots[i].did === did) {
                return board.slots[i].cameraName;
            }
        }
        return did;
    }

    /* ===================== 状态条 ===================== */

    function showNotice(text, kind) {
        var n = document.getElementById('notice');
        n.textContent = text;
        n.className = kind === 'info' ? 'notice info' : 'notice';
        n.hidden = false;
    }

    function hideNotice() {
        document.getElementById('notice').hidden = true;
    }

    /* ===================== 轮询 reconcile ===================== */

    async function reconcile() {
        try {
            var b = await fetchBoard();
            if (!b) {
                return;
            }
            board = b;
            b.slots.forEach(function (s) {
                var rec = players.get(s.slot);
                if (!rec) {
                    var cell = document.querySelector('.cell[data-slot="' + CSS.escape(s.slot) + '"]');
                    if (cell) { startPlayer(cell, s); }
                    return;
                }
                if (rec.online !== s.online) {
                    rec.online = s.online;
                    if (s.online) {
                        rec.hlsUrl = s.hlsUrl;
                        attachHls(rec);
                    } else {
                        if (rec.hls) {
                            try { rec.hls.destroy(); } catch (e) { /* 忽略 */ }
                            rec.hls = null;
                        }
                        rec.video.pause();
                        showPlaceholder(rec.cell, '设备离线', 'offline');
                    }
                }
                /* 事件进行中跳过 did 替换，避免破坏交换，待 end 后下个周期处理 */
                if (!eventState && rec.did !== s.did) {
                    destroyPlayer(rec);
                    var target = rec.cell;
                    target.querySelector('.cell-bar').remove();
                    target.appendChild(buildBar(s));
                    startPlayer(target, s);
                }
            });
        } catch (e) {
            /* 轮询失败保留现状，下个周期再试 */
        }
    }

    /* ===================== 通用 ===================== */

    function fatal(msg) {
        var boot = document.getElementById('boot');
        boot.textContent = msg;
        boot.hidden = false;
        document.getElementById('board').hidden = true;
    }

    /* ===================== 启动 ===================== */

    async function init() {
        try {
            var b = await fetchBoard();
            if (!b) {
                return;
            }
            board = b;
            // 用服务端生效的周期覆盖内置默认（pageReloadSec=0 表示关闭兜底重载）
            if (b.configPollSec > 0) { CONFIG_POLL_SEC = b.configPollSec; }
            PAGE_RELOAD_SEC = b.pageReloadSec > 0 ? b.pageReloadSec : 0;
            render(b);
            connectSSE();

            setInterval(reconcile, CONFIG_POLL_SEC * 1000);
            /* 兜底重载：防止长期运行内存膨胀/假死；周期为 0 时不设定时器 */
            if (PAGE_RELOAD_SEC > 0) {
                setTimeout(function () { location.reload(); }, PAGE_RELOAD_SEC * 1000);
            }
        } catch (e) {
            document.getElementById('boot').textContent = '加载失败，自动重试…';
            setTimeout(function () { location.reload(); }, 5000);
        }
    }

    init();
})();
