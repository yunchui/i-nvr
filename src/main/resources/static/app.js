/* iNvr Web 单页逻辑：登录分步交互、设备、HLS 预览、录像管理 */
(function () {
    'use strict';

    var $ = function (id) { return document.getElementById(id); };
    var state = {
        loggedIn: false,
        userId: null,
        region: null,
        accessConfigured: false,
        unlocked: false,
        loginToken: null,
        devices: [],
        hls: null,
        pollTimer: null,
        gateTimer: null,
        recoveredOnce: false,
        // 门禁令牌（内存兜底；主存 localStorage，跨站 iframe 中为分区存储仍可读写）
        memToken: '',
        // 时间轴回放状态
        pb: {
            inited: false, did: null, dateStr: null,
            zoom: 24, winStart: 0, dayStart: 0, winDur: 86400000,
            segs: [], idx: -1, curStart: 0, pendingOffset: null,
            liveTimer: null, liveBusy: false
        }
    };

    /* ---------- 通用 ---------- */
    function toast(msg, kind) {
        var box = $('toast');
        var el = document.createElement('div');
        el.className = 'toast-item' + (kind ? ' ' + kind : '');
        el.textContent = msg;
        box.appendChild(el);
        setTimeout(function () { el.remove(); }, 2800);
    }

    function isGatePath(p) {
        return p.indexOf('api/access') === 0 || p.indexOf('api/login') === 0;
    }

    /* ---------- 门禁令牌（不依赖 Cookie，兼容跨站 iframe） ---------- */
    var TOKEN_KEY = 'invr_access_token';
    function getToken() {
        try { return localStorage.getItem(TOKEN_KEY) || state.memToken || ''; }
        catch (e) { return state.memToken || ''; }
    }
    function setToken(t) {
        state.memToken = t || '';
        try {
            if (t) { localStorage.setItem(TOKEN_KEY, t); }
            else { localStorage.removeItem(TOKEN_KEY); }
        } catch (e) { /* 存储被禁用时仅用内存兜底 */ }
    }
    /** 给无法加自定义头的 URL（<video> 媒体、<a download> 下载）追加令牌查询参数。 */
    function withToken(path) {
        var t = getToken();
        if (!t) { return path; }
        return path + (path.indexOf('?') >= 0 ? '&' : '?') + '__invr_t=' + encodeURIComponent(t);
    }

    async function api(path, opts) {
        opts = opts || {};
        opts.headers = Object.assign({}, opts.headers);
        var tk = getToken();
        if (tk && !opts.headers['Authorization']) {
            opts.headers['Authorization'] = 'Bearer ' + tk;
        }
        var res = await fetch(path, opts);
        var ct = res.headers.get('content-type') || '';
        if (ct.indexOf('application/json') >= 0) {
            var data = await res.json();
            if (data && typeof data === 'object' && data.token) { setToken(data.token); }
            if (!res.ok) {
                var msg = data.error || ('请求失败 HTTP ' + res.status);
                var err = new Error(msg);
                err.status = res.status;
                err.code = data.code;
                // 门禁失效（服务重启 / 会话过期 / 未解锁）：自动回到设置或解锁页（门禁/登录接口除外）
                if (res.status === 401 && data.code === 'ACCESS_LOCKED') { scheduleGateRelock(); }
                throw err;
            }
            return data;
        }
        if (!res.ok) {
            if (res.status === 401) { scheduleGateRelock(); }
            throw new Error('请求失败 HTTP ' + res.status);
        }
        return res;
    }

    function fmtSize(n) {
        if (n == null) return '-';
        if (n < 1024) return n + ' B';
        if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
        if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
        return (n / 1073741824).toFixed(2) + ' GB';
    }
    function fmtTime(ms) {
        var d = new Date(ms);
        function p(x) { return String(x).padStart(2, '0'); }
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
            ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }
    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    /* ---------- 视图切换 ---------- */
    function stopPoll() {
        if (state.pollTimer) { clearInterval(state.pollTimer); state.pollTimer = null; }
    }
    function hideAll() {
        $('loginView').hidden = true;
        $('setupView').hidden = true;
        $('lockView').hidden = true;
        $('appView').hidden = true;
        $('topRight').hidden = true;
        stopPoll();
        if (!$('previewModal').hidden) stopPreviewOnly();
        pausePb();
    }
    function showLogin() {
        hideAll();
        $('loginView').hidden = false;
        resetLoginPanels();
    }
    function showSetup() {
        hideAll();
        $('setupView').hidden = false;
        $('setupPw1').value = '';
        $('setupPw2').value = '';
        $('setupError').hidden = true;
        $('setupError').textContent = '';
        setTimeout(function () { $('setupPw1').focus(); }, 0);
    }
    function showLock() {
        hideAll();
        $('lockView').hidden = false;
        $('lockPw').value = '';
        $('lockError').hidden = true;
        $('lockError').textContent = '';
        var qs = new URLSearchParams(location.search);
        if (qs.get('unlock_error') === '1') {
            showLockError('解锁链接无效或已失效，请输入访问密码。');
        }
        if (qs.has('unlock_error') || qs.has('unlocked')) {
            history.replaceState(null, '', location.pathname);
        }
        setTimeout(function () { $('lockPw').focus(); }, 0);
    }
    function showSetupError(m) {
        var b = $('setupError'); b.hidden = false; b.textContent = m;
    }
    function showLockError(m) {
        var b = $('lockError'); b.hidden = false; b.textContent = m;
    }
    function showApp(userId, region) {
        hideAll();
        $('appView').hidden = false;
        $('topRight').hidden = false;
        $('userChip').textContent = '已登录 · ' + userId + '（' + region + '）';
        resetLoginPanels();
        loadDevices(true);
        loadRecordings();
        state.pollTimer = setInterval(function () { loadDevices(false); }, 5000);
    }

    /* ---------- 访问密码门禁路由 ---------- */
    function route() {
        if (!state.accessConfigured) {
            // 尚未设置访问密码：米家已登录则强制设置，否则先登录米家
            if (state.loggedIn) { showSetup(); } else { showLogin(); }
            return;
        }
        if (!state.unlocked) { showLock(); return; }
        if (state.loggedIn) { showApp(state.userId, state.region); } else { showLogin(); }
    }

    async function refreshGate() {
        try {
            var r = await Promise.all([api('api/session'), api('api/access/status')]);
            var s = r[0], a = r[1];
            state.loggedIn = !!s.loggedIn;
            state.userId = s.userId || a.userId || null;
            state.region = s.region || a.region || null;
            state.accessConfigured = !!a.configured;
            state.unlocked = !!a.unlocked;
        } catch (e) {
            state.loggedIn = false;
            state.unlocked = false;
        }
        route();
    }

    function scheduleGateRelock() {
        if (state.gateTimer) clearTimeout(state.gateTimer);
        state.gateTimer = setTimeout(function () { state.gateTimer = null; refreshGate(); }, 300);
    }

    /* 米家登录分步走到 DONE 后调用。DONE 响应本身就是登录成功的权威结果（带 userId/region），
       先据此置登录态；门禁配置再查 /api/access/status，但不让其瞬时 loggedIn 把刚成功的登录
       覆盖回未登录（后端已保证 DONE 前会话落盘，这里是前端再兜底）。最终交给 route() 决策。 */
    async function enterAfterLogin(s) {
        state.loggedIn = true;
        if (s.userId) state.userId = s.userId;
        if (s.region) state.region = s.region;
        try {
            var a = await api('api/access/status');
            state.accessConfigured = !!a.configured;
            state.unlocked = !!a.unlocked;
            if (a.userId) { state.userId = a.userId; state.region = a.region; }
        } catch (e) { /* 门禁状态查询失败也先按“已登录”路由，setup/后续接口会再校验 */ }
        route();
    }
    function resetLoginPanels() {
        $('loginForm').hidden = false;
        $('captchaPanel').hidden = true;
        $('twofaPanel').hidden = true;
        $('loginError').hidden = true;
        $('loginError').textContent = '';
        $('captchaCode').value = '';
        $('verifyCode').value = '';
        state.loginToken = null;
    }
    function showLoginError(msg) {
        var box = $('loginError');
        box.hidden = false;
        box.textContent = msg;
    }

    /* ---------- 登录状态机 ---------- */
    function handleStage(s) {
        state.loginToken = s.token || state.loginToken;
        if (s.loggedIn || s.stage === 'DONE') {
            toast('米家账号登录成功', 'ok');
            enterAfterLogin(s);
            return;
        }
        if (s.stage === 'NEED_CAPTCHA') {
            $('loginForm').hidden = true;
            $('twofaPanel').hidden = true;
            $('captchaPanel').hidden = false;
            $('captchaImg').src = 'api/login/' + state.loginToken + '/captcha?t=' + Date.now();
            $('captchaCode').focus();
        } else if (s.stage === 'NEED_2FA') {
            $('loginForm').hidden = true;
            $('captchaPanel').hidden = true;
            $('twofaPanel').hidden = false;
            var kind = s.verifyKind === 'Email' ? '邮箱' : '手机';
            $('twofaTip').textContent = '验证码已发送至' + kind + (s.verifyTarget ? '（' + s.verifyTarget + '）' : '') + '，请输入：';
            $('verifyCode').focus();
        } else if (s.stage === 'ERROR') {
            showLoginError(s.error || '登录失败，请重试');
            resetPanelsKeepTokenFail();
        } else {
            showLoginError('登录未完成（' + s.stage + '），请重试');
            resetPanelsKeepTokenFail();
        }
    }
    function resetPanelsKeepTokenFail() {
        $('loginForm').hidden = false;
        $('captchaPanel').hidden = true;
        $('twofaPanel').hidden = true;
    }

    async function startLogin() {
        var username = $('username').value.trim();
        var password = $('password').value;
        if (!username || !password) { showLoginError('请输入账号和密码'); return; }
        $('btnLogin').disabled = true;
        try {
            var s = await api('api/login/start', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: username, password: password, region: 'cn' })
            });
            handleStage(s);
        } catch (e) { showLoginError(e.message); }
        finally { $('btnLogin').disabled = false; }
    }

    async function submitCaptcha() {
        var code = $('captchaCode').value.trim();
        if (!code) { toast('请输入图形验证码', 'err'); return; }
        try {
            var s = await api('api/login/' + state.loginToken + '/captcha', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ code: code })
            });
            handleStage(s);
        } catch (e) { showLoginError(e.message); }
    }

    async function submitVerify() {
        var code = $('verifyCode').value.trim();
        if (!code) { toast('请输入验证码', 'err'); return; }
        try {
            var s = await api('api/login/' + state.loginToken + '/verify', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ code: code })
            });
            handleStage(s);
        } catch (e) { showLoginError(e.message); }
    }

    /* ---------- 访问密码：设置 / 解锁 / 锁定 ---------- */
    async function submitSetup() {
        var p1 = $('setupPw1').value, p2 = $('setupPw2').value;
        if (!p1 || !p2) { showSetupError('请输入并再次确认访问密码'); return; }
        if (p1.length < 6) { showSetupError('访问密码至少 6 位'); return; }
        if (p1 !== p2) { showSetupError('两次输入的密码不一致'); return; }
        $('btnSetup').disabled = true;
        try {
            await api('api/access/setup', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: p1 })
            });
            state.accessConfigured = true;
            state.unlocked = true;
            toast('访问密码已设置', 'ok');
            route();
        } catch (e) { showSetupError(e.message); }
        finally { $('btnSetup').disabled = false; }
    }

    async function submitUnlock() {
        var p = $('lockPw').value;
        if (!p) { showLockError('请输入访问密码'); return; }
        $('btnUnlock').disabled = true;
        try {
            await api('api/access/unlock', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: p })
            });
            state.unlocked = true;
            var s = await api('api/session');
            state.loggedIn = !!s.loggedIn;
            state.userId = s.userId || state.userId;
            state.region = s.region || state.region;
            toast('已解锁', 'ok');
            route();
        } catch (e) { showLockError(e.message); }
        finally { $('btnUnlock').disabled = false; }
    }

    async function lockConsole() {
        try { await api('api/access/lock', { method: 'POST' }); } catch (e) {}
        state.unlocked = false;
        route();
    }

    /* 凭访问密码生成一键解锁链接（不改变当前会话状态），在设置页展示并供复制。 */
    async function genUnlockLink() {
        var p = $('setLinkPw').value;
        if (!p) { showPwError('请输入当前访问密码以生成一键解锁链接'); return; }
        $('btnGenLink').disabled = true;
        try {
            var data = await api('api/access/unlock-link', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: p })
            });
            $('setLinkText').value = location.origin + data.path;
            $('setLinkBox').hidden = false;
            $('setLinkPw').value = '';
            $('setPwError').hidden = true;
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            showPwError(e.message || '生成失败');
        }
        finally { $('btnGenLink').disabled = false; }
    }

    function copyUnlockLink() {
        var v = $('setLinkText').value;
        if (v) { copyText(v); }
    }

    /* ---------- 设备 ---------- */
    async function loadDevices(forceRefresh) {
        try {
            var data = await api('api/devices?refresh=' + (forceRefresh ? 'true' : 'false'));
            var list = data.devices || [];
            var structureChanged = list.length !== state.devices.length ||
                list.some(function (c) {
                    var old = state.devices.find(function (o) { return String(o.did) === String(c.did); });
                    return !old || old.name !== c.name || old.model !== c.model || old.localIp !== c.localIp;
                });
            state.devices = list;
            renderRecFilter();
            if (forceRefresh || structureChanged) {
                renderDevices();
            } else {
                updateDeviceStates();
            }
        } catch (e) {
            if (e.status === 401) { return; } // 门禁失效由全局 scheduleGateRelock 切回门禁页
            if (forceRefresh) toast('加载设备失败：' + e.message, 'err');
        }
    }

    function statusBadges(c) {
        if (c.previewing) return '<span class="badge live">预览中</span>' +
            (c.recording ? '<span class="badge rec">录像中</span>' : '');
        if (c.recording) return '<span class="badge rec">录像中</span>';
        return '<span class="badge off">空闲</span>';
    }

    function onlineBadge(c) {
        return '<span class="net ' + (c.online ? 'online' : 'offline') + '"><span class="dot"></span>'
            + (c.online ? '在线' : '离线') + '</span>';
    }

    function cardHtml(c) {
        var offlineDis = c.online ? '' : ' disabled title="设备离线，暂不可用"';
        // 录像中的“停止录像”始终可用（本地操作）；离线时仅禁用“开始录像”。
        var recBtn = c.recording
            ? '<button class="btn rec-on" data-act="recstop" data-did="' + esc(c.did) + '">停止录像</button>'
            : '<button class="btn" data-act="recstart" data-did="' + esc(c.did) + '"' + offlineDis + '>开始录像</button>';
        return '<div class="cam-card' + (c.online ? '' : ' offline') + '" data-did="' + esc(c.did) + '">' +
            '<div class="cam-top"><div>' +
            '<div class="cam-name"><span class="nm">' + esc(c.name) + '</span>' + onlineBadge(c) + '</div>' +
            '<div class="cam-model">' + esc(c.model) + '</div>' +
            '<div class="cam-ip">' + esc(c.localIp || '未知IP') + '</div>' +
            '</div><div class="badges">' + statusBadges(c) + '</div></div>' +
            '<div class="cam-actions">' +
            '<button class="btn primary" data-act="preview" data-did="' + esc(c.did) + '"' + offlineDis + '>实时预览</button>' +
            recBtn +
            '</div></div>';
    }

    function renderDevices() {
        var grid = $('deviceGrid');
        $('camCount').textContent = state.devices.length + ' 台摄像头';
        grid.dataset.count = String(state.devices.length);
        if (!state.devices.length) {
            grid.innerHTML = '<div class="empty">账号下未发现摄像头</div>';
            return;
        }
        grid.innerHTML = state.devices.map(cardHtml).join('');
    }

    /** 轮询只更新状态徽标、在线态与录像按钮，不重建卡片（避免按钮闪烁 / 点击中断）。 */
    function updateDeviceStates() {
        var grid = $('deviceGrid');
        state.devices.forEach(function (c) {
            var card = grid.querySelector('.cam-card[data-did="' + CSS.escape(c.did) + '"]');
            if (!card) { renderDevices(); return; }
            card.querySelector('.badges').innerHTML = statusBadges(c);
            applyOnline(card, c);
            var btn = card.querySelector('button[data-act="recstart"],button[data-act="recstop"]');
            if (btn) {
                btn.setAttribute('data-act', c.recording ? 'recstop' : 'recstart');
                btn.textContent = c.recording ? '停止录像' : '开始录像';
                btn.className = c.recording ? 'btn rec-on' : 'btn';
                btn.disabled = c.recording ? false : !c.online;
                btn.title = (!c.recording && !c.online) ? '设备离线，暂不可用' : '';
            }
        });
    }

    /** 同步在线徽标、卡片置灰与“实时预览”按钮禁用（离线不影响停止录像）。 */
    function applyOnline(card, c) {
        card.classList.toggle('offline', !c.online);
        var net = card.querySelector('.net');
        if (net) {
            net.className = 'net ' + (c.online ? 'online' : 'offline');
            net.innerHTML = '<span class="dot"></span>' + (c.online ? '在线' : '离线');
        }
        var pb = card.querySelector('button[data-act="preview"]');
        if (pb) {
            pb.disabled = !c.online;
            pb.title = c.online ? '' : '设备离线，暂不可用';
        }
    }

    function findDevice(did) {
        return state.devices.find(function (c) { return String(c.did) === String(did); });
    }

    /** 摄像头展示名：有名称则“名称（DID）”，查不到名称时仅显示 DID。 */
    function cameraLabel(did) {
        var c = findDevice(did);
        return (c && c.name) ? (c.name + '（' + did + '）') : String(did);
    }

    /* 将秒数格式化为友好的分段时长（120 -> 2 分钟、90 -> 1 分 30 秒、10 -> 10 秒）。 */
    function formatSeg(sec) {
        sec = parseInt(sec, 10) || 0;
        if (sec > 0 && sec % 60 === 0) return (sec / 60) + ' 分钟';
        if (sec >= 60) return Math.floor(sec / 60) + ' 分 ' + (sec % 60) + ' 秒';
        return sec + ' 秒';
    }

    /* ---------- 录像 ---------- */
    async function startRecord(did) {
        // 提示用当前生效的分段时长（设置页加载过则直接用缓存，否则实时取一次）
        var seg = state.settings && state.settings.segmentSec;
        if (seg == null) {
            try {
                state.settings = await api('api/settings');
                seg = state.settings.segmentSec;
            } catch (e) {
                seg = 120; // 仅用于提示文案兜底，实际录制时长以后端生效配置为准
            }
        }
        if (!confirm('确认开始录像？将按 ' + formatSeg(seg) + ' 分段保存为 MP4。')) return;
        try {
            await api('api/record/' + did + '/start', {
                method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}'
            });
            toast('录像已开始', 'ok');
            loadDevices(false); loadRecordings();
        } catch (e) { toast(e.message, 'err'); }
    }
    async function stopRecord(did) {
        try {
            var r = await api('api/record/' + did + '/stop', { method: 'POST' });
            toast('已停止，本段录像已保存（共 ' + (r.segments || 0) + ' 段）', 'ok');
            loadDevices(false); loadRecordings();
        } catch (e) { toast(e.message, 'err'); }
    }

    async function loadRecordings() {
        var filter = $('recFilter').value || '';
        var url = 'api/recordings' + (filter ? '?did=' + encodeURIComponent(filter) : '');
        try {
            // 确保设备名已加载，录像列表才能显示“名称（DID）”而非裸 DID
            if (!state.devices.length) { try { await loadDevices(false); } catch (e2) { /* 忽略，退回显示 DID */ } }
            var list = await api(url);
            renderRecordings(list);
        } catch (e) {
            $('recList').innerHTML = '<tr><td colspan="5" class="empty">加载失败：' + esc(e.message) + '</td></tr>';
        }
    }
    function renderRecordings(list) {
        var tb = $('recList');
        if (!list || !list.length) {
            tb.innerHTML = '<tr><td colspan="5" class="empty">暂无录像文件</td></tr>';
            return;
        }
        tb.innerHTML = list.map(function (r) {
            return '<tr>' +
                '<td class="fname">' + esc(r.name) + '</td>' +
                '<td>' + esc(cameraLabel(r.did)) + (r.recording ? ' <span class="badge rec">录制中</span>' : '') + '</td>' +
                '<td>' + fmtSize(r.size) + '</td>' +
                '<td>' + fmtTime(r.lastModified) + '</td>' +
                '<td class="acts">' +
                '<button class="btn tiny" data-ract="play" data-name="' + esc(r.name) + '">播放</button>' +
                '<a class="btn tiny" href="' + withToken('api/recordings/' + encodeURIComponent(r.name) + '?dl=1') + '" download>下载</a>' +
                '<button class="btn tiny" data-ract="del" data-name="' + esc(r.name) + '">删除</button>' +
                '</td></tr>';
        }).join('');
        tb.querySelectorAll('button[data-ract]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var name = btn.getAttribute('data-name');
                var act = btn.getAttribute('data-ract');
                if (act === 'play') openPlayer(name);
                else if (act === 'del') delRecording(name);
            });
        });
    }
    function renderRecFilter() {
        var sel = $('recFilter');
        var cur = sel.value;
        var opts = '<option value="">全部摄像头</option>' +
            state.devices.map(function (c) {
                return '<option value="' + esc(c.did) + '">' + esc(c.name) + ' (' + esc(c.did) + ')</option>';
            }).join('');
        if (sel.innerHTML !== opts) sel.innerHTML = opts;
        if (cur) sel.value = cur;
    }
    async function delRecording(name) {
        if (!confirm('确认删除录像文件？\n' + name)) return;
        try {
            await api('api/recordings/' + encodeURIComponent(name), { method: 'DELETE' });
            toast('已删除', 'ok'); loadRecordings();
        } catch (e) { toast(e.message, 'err'); }
    }

    /* ---------- 系统设置（存储路径 / 分段 / 端口 / 目录选择） ---------- */
    var FOLDER_SVG = '<svg class="dir-ico" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">' +
        '<path fill="currentColor" d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z"/></svg>';

    function setKv(k, v) {
        return '<div><span>' + esc(k) + '</span><b>' + esc(v) + '</b></div>';
    }

    async function loadSettings() {
        try {
            var s = await api('api/settings');
            state.settings = s;
            $('setCurDir').textContent = s.recordingDir;
            $('setDefaultDir').textContent = s.defaultDir;
            $('setStats').innerHTML =
                '<span>录像文件 <b>' + (s.recordingCount || 0) + '</b> 个</span>' +
                '<span>录制中 <b>' + (s.activeCount || 0) + '</b> 路</span>' +
                '<span>可用空间 <b>' + fmtSize(s.freeSpace) + '</b> / ' + fmtSize(s.totalSpace) + '</span>';
            $('setMisc').innerHTML = setKv('程序数据目录', s.dataDir);
            $('setError').hidden = true;
            $('setError').textContent = '';
            // 未选择新目录前禁用保存
            $('setDirInput').value = '';
            $('btnSaveDir').disabled = true;
            // 分段时长
            $('setSegmentInput').value = s.segmentSec;
            $('setSegmentHint').textContent = (s.segmentCustomized ? '当前为自定义值，默认 ' + s.defaultSegmentSec + ' 秒。' : '当前为默认值。')
                + '保存后正在录制的分段会封段并按新时长续录。';
            // RTSP 端口
            $('setRtspInput').value = s.rtspPort;
            $('setRtspHint').textContent = '当前为' + (s.rtspCustomized ? '自定义' : '默认') + '端口，默认 ' + s.defaultRtspPort
                + '。保存后立即切换，现有 RTSP 播放器会短暂断开，需用新地址重连。';
            // 录像保留天数
            $('setRetentionInput').value = s.retentionDays;
            $('setRetentionHint').textContent = (s.retentionDays > 0)
                ? '当前保留最近 ' + s.retentionDays + ' 天，超期录像每小时自动清理（'
                    + (s.retentionCustomized ? '自定义值' : '默认值') + '，默认 ' + s.defaultRetentionDays + ' 天）。'
                : '当前为永久保存，不自动清理（0 表示永久；默认值为 ' + s.defaultRetentionDays + ' 天）。';
        } catch (e) {
            if (e.status === 401) return; // 门禁失效由全局逻辑切回解锁页
            toast('加载设置失败：' + e.message, 'err');
        }
    }

    async function saveSettings(reset) {
        var val = reset ? '' : $('setDirInput').value.trim();
        if (!reset && !val) {
            toast('请先点击“浏览”选择一个目录', 'err');
            return;
        }
        if (!confirm(reset
            ? '确认恢复为默认存储目录？\n当前正在录制的分段会在封段后写入默认目录。'
            : '确认将录像存储路径修改为：\n' + val + '\n\n正在录制的分段会在当前段封段后写入新目录，已有录像不会自动移动。')) {
            return;
        }
        $('btnSaveDir').disabled = true;
        $('btnResetDir').disabled = true;
        try {
            var s = await api('api/settings', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ recordingDir: val })
            });
            toast('存储路径已应用：' + s.recordingDir, 'ok');
            $('setDirInput').value = '';
            await loadSettings();
            loadRecordings();
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            var box = $('setError');
            box.hidden = false;
            box.textContent = e.message;
        } finally {
            $('btnResetDir').disabled = false;
        }
    }

    async function saveSegment() {
        var sec = parseInt($('setSegmentInput').value, 10);
        if (isNaN(sec) || sec < 10 || sec > 3600) {
            toast('分段时长需在 10～3600 秒之间', 'err');
            return;
        }
        if (!confirm('确认将录像分段时长改为 ' + sec + ' 秒？\n正在录制的分段会正常封段后按新时长续录。')) return;
        var btn = $('btnSaveSegment');
        btn.disabled = true;
        try {
            var s = await api('api/settings/segment', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ segmentSec: sec })
            });
            toast('分段时长已保存：' + s.segmentSec + ' 秒', 'ok');
            await loadSettings();
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('保存失败：' + e.message, 'err');
        } finally {
            btn.disabled = false;
        }
    }

    async function saveRtsp() {
        var port = parseInt($('setRtspInput').value, 10);
        if (isNaN(port) || port < 1024 || port > 65535) {
            toast('RTSP 端口需在 1024～65535 之间', 'err');
            return;
        }
        if (!confirm('确认将 RTSP 端口改为 ' + port + '？\n现有 RTSP 播放器会断开，需用新端口重连。')) return;
        var btn = $('btnSaveRtsp');
        btn.disabled = true;
        try {
            var s = await api('api/settings/rtsp', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ rtspPort: port })
            });
            toast('RTSP 端口已切换为 ' + s.rtspPort, 'ok');
            await loadSettings();
            loadDevices(false); // 刷新设备卡片上的 RTSP 地址
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('切换失败：' + e.message, 'err');
            await loadSettings();
        } finally {
            btn.disabled = false;
        }
    }

    async function saveRetention() {
        var days = parseInt($('setRetentionInput').value, 10);
        if (isNaN(days) || days < 0 || days > 3650) {
            toast('保留天数需在 0～3650 之间（0 表示永久保存）', 'err');
            return;
        }
        if (!confirm(days === 0
            ? '确认设置为永久保存？\n将不再自动删除历史录像。'
            : '确认将录像保留天数改为 ' + days + ' 天？\n超过该天数的录像将被自动删除。')) return;
        var btn = $('btnSaveRetention');
        btn.disabled = true;
        try {
            var s = await api('api/settings/retention', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ retentionDays: days })
            });
            toast(days === 0 ? '已设置为永久保存' : '保留天数已保存：' + s.retentionDays + ' 天', 'ok');
            await loadSettings();
            loadRecordings();
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            toast('保存失败：' + e.message, 'err');
        } finally {
            btn.disabled = false;
        }
    }

    function showPwError(msg) {
        var box = $('setPwError');
        box.hidden = false;
        box.textContent = msg;
    }

    async function changeAccessPassword() {
        var oldPw = $('setPwOld').value;
        var newPw = $('setPwNew').value;
        var newPw2 = $('setPwNew2').value;
        $('setPwError').hidden = true;
        $('setPwError').textContent = '';
        if (!oldPw) { showPwError('请输入当前密码'); return; }
        if (!newPw || newPw.length < 6) { showPwError('新密码至少 6 位'); return; }
        if (newPw.length > 64) { showPwError('新密码不能超过 64 位'); return; }
        if (newPw.trim() === '') { showPwError('新密码不能全为空白字符'); return; }
        if (newPw !== newPw2) { showPwError('两次输入的新密码不一致'); return; }
        if (!confirm('确认修改门禁访问密码？\n修改后需用新密码解锁，原一键解锁链接将失效。')) return;
        var btn = $('btnSavePw');
        btn.disabled = true;
        try {
            var r = await api('api/settings/password', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ oldPassword: oldPw, newPassword: newPw })
            });
            $('setPwOld').value = ''; $('setPwNew').value = ''; $('setPwNew2').value = '';
            toast('门禁密码已修改，当前会话保持登录', 'ok');
            if (r.unlockPath) {
                try { prompt('密码已修改。新的一键解锁链接（原链接已失效，可复制保存）：', location.origin + r.unlockPath); } catch (e2) { /* 忽略弹窗受限场景 */ }
            }
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); return; }
            showPwError(e.message || '修改失败');
        } finally {
            btn.disabled = false;
        }
    }

    /* ---------- 服务器目录选择弹窗 ---------- */
    var dirData = { current: null, parent: null, roots: [], entries: [] };
    var dirPicked = null;

    function openDirModal() {
        dirPicked = null;
        $('dirConfirm').disabled = true;
        $('dirSelected').textContent = '未选择';
        $('dirModal').hidden = false;
        // 从当前存储目录开始浏览，缺省从盘符/根开始
        var start = (state.settings && state.settings.recordingDir) ? state.settings.recordingDir : '';
        loadDir(start);
    }

    function closeDirModal() {
        $('dirModal').hidden = true;
    }

    async function loadDir(path) {
        var box = $('dirList');
        box.innerHTML = '<div class="dir-empty">加载中…</div>';
        $('dirStatus').textContent = '';
        try {
            var q = path ? ('?path=' + encodeURIComponent(path)) : '';
            dirData = await api('api/settings/browse' + q);
            renderDir();
        } catch (e) {
            if (e.status === 401) { scheduleGateRelock(); closeDirModal(); return; }
            box.innerHTML = '';
            $('dirStatus').textContent = '无法打开该目录：' + e.message;
        }
    }

    function renderDir() {
        var d = dirData;
        $('dirCurrent').textContent = d.current || '此电脑 / 根目录';
        $('dirUp').disabled = !d.parent;
        $('dirRoots').innerHTML = (d.roots || []).map(function (r) {
            return '<button class="btn tiny dir-drive" type="button" data-path="' + esc(r.path) + '">' + esc(r.name) + '</button>';
        }).join('');
        var box = $('dirList');
        if (!d.entries || !d.entries.length) {
            box.innerHTML = '<div class="dir-empty">（此目录下没有子目录）</div>';
        } else {
            box.innerHTML = d.entries.map(function (e) {
                var cls = e.writable ? 'dir-item' : 'dir-item ro';
                return '<div class="' + cls + '" data-path="' + esc(e.path) + '" data-writable="' + (e.writable ? '1' : '0') + '">' +
                    FOLDER_SVG +
                    '<span class="dir-name">' + esc(e.name) + '</span>' +
                    '<span class="dir-flag">' + (e.writable ? '可写' : '不可写') + '</span>' +
                    '</div>';
            }).join('');
        }
        box.querySelectorAll('.dir-item').forEach(function (el) {
            var p = el.getAttribute('data-path');
            var writable = el.getAttribute('data-writable') === '1';
            el.addEventListener('click', function () {
                box.querySelectorAll('.dir-item').forEach(function (x) { x.classList.remove('sel'); });
                el.classList.add('sel');
                dirPicked = writable ? p : null;
                $('dirSelected').textContent = writable ? p : '该目录不可写，无法选择';
                $('dirConfirm').disabled = !writable;
            });
            el.addEventListener('dblclick', function () {
                loadDir(p); // 双击进入（不可写也可尝试进入查看）
            });
        });
        $('dirRoots').querySelectorAll('.dir-drive').forEach(function (b) {
            b.addEventListener('click', function () { loadDir(b.getAttribute('data-path')); });
        });
        if (d.unreadable) {
            $('dirStatus').textContent = '该目录无权限列出内容。';
        }
    }

    function confirmDir() {
        if (!dirPicked) {
            toast('请选择一个可写目录', 'err');
            return;
        }
        $('setDirInput').value = dirPicked;
        $('btnSaveDir').disabled = false;
        closeDirModal();
    }

    /* ---------- HLS 预览 ---------- */
    // 冷启动时服务端首个 fMP4 分片尚未生成（需先连上摄像头并缓存一个 IDR）。原生 HLS 对
    // “无 ENDLIST 的直播播放列表”要求至少攒够约 3 个分片才可起播；若在只有 1 个分片时就赋
    // src，会直接判 MEDIA_ERR_SRC_NOT_SUPPORTED(error4) 且不可恢复。这里串行轮询 m3u8（同时
    // 也在保活 HLS 订阅），等列表里稳定出现 >=3 个 .m4s 分片后再赋 src 播放。
    function warmHls(u, cb) {
        var done = false, tries = 0;
        function finish() { if (!done) { done = true; cb(); } }
        function segCount(txt) { return (txt.match(/\.m4s/g) || []).length; }
        function once() {
            tries++;
            fetch(u, { cache: 'no-store' }).then(function (r) { return r.text(); }).then(function (txt) {
                if (segCount(txt) >= 3 || tries >= 25) { finish(); }
                else { setTimeout(once, 600); }
            }).catch(function () { if (tries >= 25) { finish(); } else { setTimeout(once, 600); } });
        }
        once();
    }

    function openPreview(did) {
        var c = findDevice(did);
        if (!c) { toast('设备信息缺失', 'err'); return; }
        $('previewTitle').textContent = '实时预览 · ' + c.name;
        $('previewRtsp').textContent = c.rtspUrl;
        $('previewModal').hidden = false;
        $('previewHint').hidden = true;
        $('previewLoading').hidden = false;
        var video = $('previewVideo');
        video.style.visibility = 'visible';
        state.recoveredOnce = false;

        var clearWatch = function () {
            if (state.previewWatch) { clearTimeout(state.previewWatch); state.previewWatch = null; }
        };
        var codecHint = '当前浏览器无法解码 H.265(HEVC) 或取流未成功。\n请用 Edge 并安装「HEVC 视频扩展」，或复制下方 RTSP 地址用 VLC 播放。';
        // 看门狗：预热攒片（最多约15s）+ 首片加载/3.5K 软解后，26s 内仍未解码出画面则明确提示
        clearWatch();
        state.previewWatch = setTimeout(function () {
            state.previewWatch = null;
            if ($('previewModal').hidden) return;
            if (!video.videoWidth || video.readyState < 2) {
                if (state.hls) { try { state.hls.destroy(); } catch (e) {} state.hls = null; }
                showPreviewHint(codecHint);
            }
        }, 26000);
        video.addEventListener('loadeddata', clearWatch, { once: true });
        video.addEventListener('playing', clearWatch, { once: true });
        video.addEventListener('error', function () {
            if (!$('previewModal').hidden && !video.videoWidth) showPreviewHint(codecHint);
        }, { once: true });

        // 媒体 URL 追加令牌查询参数（原生 HLS 子资源由服务端 m3u8 注入令牌，此处先覆盖 m3u8 本身）
        var url = withToken(c.hlsUrl);
        if (state.hls) { try { state.hls.destroy(); } catch (e) {} state.hls = null; }

        if (video.canPlayType('application/vnd.apple.mpegurl')) {
            // Safari / Edge 原生 HLS：先预热等首个 fMP4 分片生成，再赋 src 播放
            warmHls(url, function () {
                if (!$('previewModal').hidden) {
                    video.src = url;
                    video.play().catch(function () {});
                }
            });
        } else if (window.Hls && Hls.isSupported()) {
            // hls.js：除 m3u8 内 URI 携带令牌外，所有 XHR 再注入 Authorization 头（双保险）
            var hlsOpts = { liveSyncDurationCount: 3, lowLatencyMode: false, enableWorker: true };
            var ht = getToken();
            if (ht) {
                hlsOpts.xhrSetup = function (xhr) {
                    try { xhr.setRequestHeader('Authorization', 'Bearer ' + ht); } catch (e) {}
                };
            }
            var hls = new Hls(hlsOpts);
            state.hls = hls;
            hls.loadSource(url);
            hls.attachMedia(video);
            hls.on(Hls.Events.MANIFEST_PARSED, function () {
                video.play().catch(function () {});
            });
            hls.on(Hls.Events.ERROR, function (evt, data) {
                if (!data.fatal) return;
                if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
                    if (!state.recoveredOnce) { state.recoveredOnce = true; hls.startLoad(); }
                    else showPreviewHint('取流中断，已尝试重连失败。请关闭后重新预览，或用 RTSP 在 VLC 播放。');
                } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
                    var codecFail = data.details === Hls.ErrorDetails.MANIFEST_INCOMPATIBLE_CODECS_ERROR ||
                        data.details === Hls.ErrorDetails.BUFFER_ADD_CODEC_ERROR;
                    if (codecFail) {
                        showPreviewHint('当前浏览器无法解码 H.265(HEVC)。\n请使用 Edge / 安装「HEVC 视频扩展」，或复制下方 RTSP 地址用 VLC 播放。');
                        hls.destroy();
                    } else if (!state.recoveredOnce) {
                        state.recoveredOnce = true;
                        try { hls.recoverMediaError(); } catch (e) {
                            showPreviewHint('视频解码失败，建议用 RTSP 在 VLC 播放。');
                        }
                    } else {
                        showPreviewHint('视频解码失败，建议复制下方 RTSP 地址用 VLC 播放。');
                    }
                } else {
                    showPreviewHint('播放失败：' + (data.details || '未知错误') + '。可改用 RTSP 在 VLC 播放。');
                }
            });
        } else {
            showPreviewHint('当前浏览器不支持 HLS 播放，请复制下方 RTSP 地址用 VLC 播放。');
        }
    }
    function showPreviewHint(text) {
        var h = $('previewHint');
        h.hidden = false;
        h.style.whiteSpace = 'pre-line';
        h.textContent = text;
        $('previewLoading').hidden = true;
    }
    function stopPreviewOnly() {
        $('previewModal').hidden = true;
        var video = $('previewVideo');
        if (state.previewWatch) { clearTimeout(state.previewWatch); state.previewWatch = null; }
        if (state.hls) { try { state.hls.destroy(); } catch (e) {} state.hls = null; }
        video.pause();
        video.removeAttribute('src');
        video.load();
        $('previewHint').hidden = true;
        $('previewLoading').hidden = true;
    }
    function closePreview() {
        stopPreviewOnly();
        loadDevices(false);
    }

    /* ---------- 录像回放 ---------- */
    function openPlayer(name) {
        $('playerTitle').textContent = '录像回放 · ' + name;
        $('playerModal').hidden = false;
        var v = $('playerVideo');
        v.src = withToken('api/recordings/' + encodeURIComponent(name));
        v.play().catch(function () {});
    }
    function closePlayer() {
        $('playerModal').hidden = true;
        var v = $('playerVideo');
        v.pause(); v.removeAttribute('src'); v.load();
    }

    /* ---------- 时间轴回放 ---------- */
    function pad2(x) { return String(x).padStart(2, '0'); }
    function localDateStr(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
    function hms(ms) { var d = new Date(ms); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds()); }

    /** 回放页时间轴静默刷新周期：让新封段的分段与“录制中”块的增长及时反映出来。 */
    var PB_LIVE_MS = 15000;

    function startPbLive() {
        var pb = state.pb;
        if (pb.liveTimer) return;
        pb.liveTimer = setInterval(refreshTimelineLive, PB_LIVE_MS);
    }

    function stopPbLive() {
        var pb = state.pb;
        if (pb.liveTimer) { clearInterval(pb.liveTimer); pb.liveTimer = null; }
    }

    function enterPlayback() {
        renderPbCam();
        var pb = state.pb;
        var today = localDateStr(new Date());
        $('pbDate').max = today;
        if (!pb.dateStr) { $('pbDate').value = today; pb.dateStr = today; }
        else { $('pbDate').value = pb.dateStr; }
        if (pb.did) { $('pbCam').value = pb.did; }
        else if (state.devices.length) { pb.did = String(state.devices[0].did); $('pbCam').value = pb.did; }
        setZoomUi();
        startPbLive();
        if (!pb.inited) { pb.inited = true; loadTimeline(); }
    }

    function renderPbCam() {
        var sel = $('pbCam');
        var cur = sel.value;
        sel.innerHTML = state.devices.map(function (c) {
            return '<option value="' + esc(c.did) + '">' + esc(cameraLabel(c.did)) + '</option>';
        }).join('');
        if (cur) sel.value = cur;
    }

    function pausePb() {
        var el = (typeof $ === 'function') ? $('pbVideo') : null;
        if (el && !el.paused) { el.pause(); }
    }

    async function loadTimeline() {
        var pb = state.pb;
        pb.did = $('pbCam').value || pb.did;
        pb.dateStr = $('pbDate').value || localDateStr(new Date());
        if (!pb.did) { $('tlBlocks').innerHTML = '<div class="tl-no">请先选择摄像头</div>'; return; }
        pb.dayStart = new Date(pb.dateStr + 'T00:00:00').getTime();
        pb.idx = -1; pb.curStart = 0;
        try {
            var data = await api('api/timeline/' + encodeURIComponent(pb.did) + '?date=' + encodeURIComponent(pb.dateStr));
            pb.segs = data.segments || [];
        } catch (e) { pb.segs = []; toast(e.message, 'err'); }
        computeWin(true);
        renderTimeline();
    }

    /** 分段集合的轻量签名（名称/起止/时长/录制态），用于判断静默刷新是否真有变化。 */
    function tlSig(segs) {
        return segs.map(function (s) {
            return s.name + '@' + s.startMs + ':' + s.durMs + (s.recording ? 'r' : '');
        }).join('|');
    }

    /**
     * 回放页停留期间的静默刷新：重新拉取当天时间轴，让“新封段的分段”出现、
     * “录制中”块随时间增长。不重置日期 / 缩放 / 窗口，也不打断正在播放的视频；
     * 当前播放段按文件名重新定位（避免新段插入导致索引漂移）。
     */
    async function refreshTimelineLive() {
        var pb = state.pb;
        if (pb.liveBusy || !pb.did) return;
        pb.liveBusy = true;
        try {
            var data = await api('api/timeline/' + encodeURIComponent(pb.did)
                + '?date=' + encodeURIComponent(pb.dateStr || localDateStr(new Date())));
            var segs = data.segments || [];
            if (tlSig(segs) === tlSig(pb.segs)) return;
            var curName = (pb.idx >= 0 && pb.segs[pb.idx]) ? pb.segs[pb.idx].name : null;
            pb.segs = segs;
            if (curName) {
                var ni = -1;
                for (var k = 0; k < segs.length; k++) { if (segs[k].name === curName) { ni = k; break; } }
                if (ni >= 0) {
                    pb.idx = ni;
                    pb.curStart = segs[ni].startMs;
                } else {
                    // 当前段已不在（如刚被保留策略清理），停止播放并复位选中
                    pb.idx = -1;
                    var pv0 = $('pbVideo');
                    pv0.pause(); pv0.removeAttribute('src'); pv0.load();
                    $('pbEmpty').hidden = false;
                }
            }
            renderTimeline();
        } catch (e) {
            // 静默失败，不打扰正在观看；门禁失效走全局 401 处理
            if (e.status === 401) { scheduleGateRelock(); }
        } finally {
            pb.liveBusy = false;
        }
    }

    function computeWin(resetAnchor) {
        var pb = state.pb;
        pb.winDur = pb.zoom * 3600 * 1000;
        var dayEnd = pb.dayStart + 86400000;
        if (pb.zoom === 24) { pb.winStart = pb.dayStart; return; }
        var anchor;
        if (resetAnchor) {
            anchor = pb.idx >= 0 ? pb.curStart
                : (pb.dateStr === localDateStr(new Date()) ? Date.now() : pb.dayStart + 9 * 3600 * 1000);
        } else { anchor = pb.winStart + pb.winDur / 2; }
        var start = anchor - pb.winDur / 2;
        pb.winStart = Math.max(pb.dayStart, Math.min(start, dayEnd - pb.winDur));
    }

    function setZoomUi() {
        document.querySelectorAll('#pbZoom [data-zoom]').forEach(function (b) {
            b.classList.toggle('primary', String(state.pb.zoom) === b.getAttribute('data-zoom'));
        });
    }

    function pct(ms) { var pb = state.pb; return (ms - pb.winStart) / pb.winDur * 100; }

    function renderTimeline() {
        var pb = state.pb;
        var steps = pb.zoom === 24 ? { tick: 3600, every: 2 }
            : pb.zoom === 6 ? { tick: 900, every: 2 }   // 15 分钟细线，30 分钟标签
            : { tick: 300, every: 3 };                // 5 分钟细线，15 分钟标签（1 小时窗）
        var ruler = $('tlRuler');
        var winEnd = pb.winStart + pb.winDur;
        var tickMs = steps.tick * 1000;
        var first = Math.ceil(pb.winStart / tickMs) * tickMs;
        var html = '';
        for (var t = first; t < winEnd; t += tickMs) {
            var nth = Math.round(t / tickMs);
            var major = (nth % steps.every === 0);
            html += '<div class="tl-tick' + (major ? ' major' : '') + '" style="left:' + pct(t).toFixed(3) + '%">'
                + (major ? '<span class="tl-lab">' + hms(t) + '</span>' : '') + '</div>';
        }
        ruler.innerHTML = html;

        var blocks = $('tlBlocks');
        if (!pb.segs.length) {
            blocks.innerHTML = '<div class="tl-no">该摄像头当天无录像</div>';
        } else {
            blocks.innerHTML = pb.segs.map(function (s, i) {
                var l = Math.max(0, pct(s.startMs));
                var r = Math.min(100, pct(s.startMs + s.durMs));
                var w = Math.max(0.4, r - l);
                var cls = 'tl-block' + (s.recording ? ' rec' : '') + (i === pb.idx ? ' cur' : '');
                var title = hms(s.startMs) + ' ~ ' + hms(s.startMs + s.durMs) + (s.recording ? '（录制中）' : '');
                return '<div class="' + cls + '" data-i="' + i + '" style="left:' + l.toFixed(3) + '%;width:' + w.toFixed(3) + '%" title="' + esc(title) + '"></div>';
            }).join('');
        }
        updateCursor();
        updateMeta();
    }

    function updateCursor() {
        var pb = state.pb;
        var cur = $('tlCursor');
        var v = $('pbVideo');
        if (pb.idx < 0 || !v.src) { cur.hidden = true; return; }
        var x = pct(pb.curStart + (v.currentTime || 0) * 1000);
        if (x < 0 || x > 100) { cur.hidden = true; return; }
        cur.hidden = false;
        cur.style.left = x.toFixed(3) + '%';
    }

    function updateMeta() {
        var pb = state.pb;
        var v = $('pbVideo');
        if (pb.idx >= 0 && v.src) {
            $('pbNow').textContent = hms(pb.curStart + (v.currentTime || 0) * 1000) + ' 播放中';
            var s = pb.segs[pb.idx];
            $('pbSeg').textContent = '分段 ' + (pb.idx + 1) + '/' + pb.segs.length + ' · ' + hms(s.startMs)
                + (s.recording ? ' · 录制中' : '');
        } else {
            $('pbNow').textContent = pb.segs.length ? ('当天共 ' + pb.segs.length + ' 段录像，点击时间轴回放') : '当天无录像';
            $('pbSeg').textContent = '';
        }
    }

    function seekTo(tMs) {
        var pb = state.pb;
        if (!pb.segs.length) { toast('该时刻没有录像', 'err'); return; }
        var i = -1, k;
        for (k = 0; k < pb.segs.length; k++) { if (pb.segs[k].startMs <= tMs) i = k; else break; }
        var offset = 0;
        if (i < 0) { i = 0; }
        else {
            var s = pb.segs[i];
            if (tMs <= s.startMs + s.durMs) {
                offset = (tMs - s.startMs) / 1000;
            } else {
                var j = -1;
                for (k = i + 1; k < pb.segs.length; k++) { if (pb.segs[k].startMs >= tMs) { j = k; break; } }
                if (j < 0) { toast('该时刻之后没有录像', 'err'); return; }
                i = j;
            }
        }
        loadSeg(i, offset);
        if (pb.zoom !== 24) { computeWin(true); }
        renderTimeline();
    }

    function loadSeg(i, offsetSec) {
        var pb = state.pb;
        if (i < 0 || i >= pb.segs.length) return;
        pb.idx = i;
        var s = pb.segs[i];
        pb.curStart = s.startMs;
        pb.pendingOffset = offsetSec || 0;
        var v = $('pbVideo');
        $('pbEmpty').hidden = true;
        v.src = withToken('api/recordings/' + encodeURIComponent(s.name));
        v.play().catch(function () {});
        if (s.recording) {
            toast('该段正在录制，播放已写入的部分（到当前进度为止）；看实时画面请用“实时预览”', '');
        }
        renderTimeline();
    }

    function pbNextSeg() {
        var pb = state.pb;
        if (pb.idx + 1 < pb.segs.length) {
            loadSeg(pb.idx + 1, 0);
            if (pb.zoom !== 24) { computeWin(true); renderTimeline(); }
        } else {
            toast('已到当天最后一段', 'ok');
            updateMeta();
        }
    }

    function autoFollow() {
        var pb = state.pb;
        if (pb.zoom === 24 || pb.idx < 0) return;
        var ms = pb.curStart + ($('pbVideo').currentTime || 0) * 1000;
        if (ms < pb.winStart || ms > pb.winStart + pb.winDur) {
            computeWin(true);
            renderTimeline();
        }
    }

    function setZoom(z) {
        state.pb.zoom = z;
        setZoomUi();
        computeWin(true);
        renderTimeline();
    }

    function pbPan(dir) {
        var pb = state.pb;
        if (pb.zoom === 24) {
            var d = new Date(pb.dateStr + 'T00:00:00');
            d.setDate(d.getDate() + dir);
            var ds = localDateStr(d);
            if (ds > localDateStr(new Date())) { return; }
            $('pbDate').value = ds;
            loadTimeline();
        } else {
            var dayEnd = pb.dayStart + 86400000;
            var ns = pb.winStart + dir * pb.winDur;
            ns = Math.max(pb.dayStart, Math.min(ns, dayEnd - pb.winDur));
            if (ns === pb.winStart) { return; }
            pb.winStart = ns;
            renderTimeline();
        }
    }

    /* ---------- 复制 ---------- */
    function copyText(text) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(function () { toast('已复制', 'ok'); },
                function () { fallbackCopy(text); });
        } else { fallbackCopy(text); }
    }
    function fallbackCopy(text) {
        var ta = document.createElement('textarea');
        ta.value = text; document.body.appendChild(ta); ta.select();
        try { document.execCommand('copy'); toast('已复制', 'ok'); }
        catch (e) { toast('复制失败，请手动选择', 'err'); }
        ta.remove();
    }

    /* ---------- 事件绑定 ---------- */
    function bind() {
        $('btnLogin').addEventListener('click', startLogin);
        $('password').addEventListener('keydown', function (e) { if (e.key === 'Enter') startLogin(); });
        $('username').addEventListener('keydown', function (e) { if (e.key === 'Enter') $('password').focus(); });
        $('btnCaptcha').addEventListener('click', submitCaptcha);
        $('captchaCode').addEventListener('keydown', function (e) { if (e.key === 'Enter') submitCaptcha(); });
        $('btnVerify').addEventListener('click', submitVerify);
        $('verifyCode').addEventListener('keydown', function (e) { if (e.key === 'Enter') submitVerify(); });
        $('captchaImg').addEventListener('click', function () {
            if (state.loginToken) this.src = 'api/login/' + state.loginToken + '/captcha?t=' + Date.now();
        });

        $('btnLogout').addEventListener('click', async function () {
            if (!confirm('确认登出米家账号？本机访问密码仍保留，重新登录后无需再设访问密码。')) return;
            try { await api('api/logout', { method: 'POST' }); } catch (e) {}
            await refreshGate();
        });
        $('btnLock').addEventListener('click', function () { lockConsole(); });
        $('btnSetup').addEventListener('click', submitSetup);
        $('btnUnlock').addEventListener('click', submitUnlock);
        $('btnGenLink').addEventListener('click', genUnlockLink);
        $('btnCopyLink').addEventListener('click', copyUnlockLink);
        $('setupPw1').addEventListener('keydown', function (e) { if (e.key === 'Enter') $('setupPw2').focus(); });
        $('setupPw2').addEventListener('keydown', function (e) { if (e.key === 'Enter') submitSetup(); });
        $('lockPw').addEventListener('keydown', function (e) { if (e.key === 'Enter') submitUnlock(); });
        $('btnRefresh').addEventListener('click', function () { loadDevices(true); loadRecordings(); });
        $('btnRecRefresh').addEventListener('click', loadRecordings);
        $('recFilter').addEventListener('change', loadRecordings);

        // 设置：存储路径 / 分段 / 端口
        $('btnBrowseDir').addEventListener('click', openDirModal);
        $('btnSaveDir').addEventListener('click', function () { saveSettings(false); });
        $('btnResetDir').addEventListener('click', function () {
            if (confirm('确认恢复为默认存储目录？\n当前正在录制的分段会在封段后写入默认目录。')) saveSettings(true);
        });
        $('btnRefreshDir').addEventListener('click', loadSettings);
        $('btnSaveSegment').addEventListener('click', saveSegment);
        $('btnSaveRtsp').addEventListener('click', saveRtsp);
        $('btnSaveRetention').addEventListener('click', saveRetention);
        $('btnSavePw').addEventListener('click', changeAccessPassword);

        // 目录选择弹窗
        $('dirClose').addEventListener('click', closeDirModal);
        $('dirCancel').addEventListener('click', closeDirModal);
        $('dirConfirm').addEventListener('click', confirmDir);
        $('dirUp').addEventListener('click', function () { if (dirData.parent) loadDir(dirData.parent); });
        $('dirReload').addEventListener('click', function () { loadDir(dirData.current); });
        $('dirModal').addEventListener('click', function (e) { if (e.target === this) closeDirModal(); });

        // 时间轴回放
        $('pbCam').addEventListener('change', loadTimeline);
        $('pbDate').addEventListener('change', loadTimeline);
        $('pbRefresh').addEventListener('click', loadTimeline);
        document.querySelectorAll('#pbZoom [data-zoom]').forEach(function (b) {
            b.addEventListener('click', function () { setZoom(parseInt(b.getAttribute('data-zoom'), 10)); });
        });
        $('pbPrev').addEventListener('click', function () { pbPan(-1); });
        $('pbNext').addEventListener('click', function () { pbPan(1); });
        $('tlTrack').addEventListener('click', function (e) {
            var rect = this.getBoundingClientRect();
            var x = e.clientX - rect.left;
            var pb = state.pb;
            var tMs = pb.winStart + (x / rect.width) * pb.winDur;
            // 优先命中视觉上的录像块：高缩放档下短段只有几像素、会被最小宽度撑宽，纯按时间
            // 反推会落到段数据范围之外（最后一段后面无段可跳，表现为点录制块无反应）。直接取
            // 被点中的块索引最稳；点在空白处再走“跳到最近段”的 seekTo 逻辑。用 elementsFromPoint
            // 遍历叠层，避免红色游标等装饰层（pointer-events:none 之外的兜底）挡住块。
            var stack = document.elementsFromPoint ? document.elementsFromPoint(e.clientX, e.clientY) : [document.elementFromPoint(e.clientX, e.clientY)];
            var block = null;
            for (var k = 0; k < stack.length; k++) {
                if (stack[k].classList && stack[k].classList.contains('tl-block')) { block = stack[k]; break; }
            }
            if (block && block.getAttribute('data-i') != null && $('tlBlocks').contains(block)) {
                var bi = parseInt(block.getAttribute('data-i'), 10);
                var bs = pb.segs[bi];
                if (bs) {
                    var boff = Math.max(0, (tMs - bs.startMs) / 1000);
                    // 录制中段末尾约一个 GOP(~3s) 尚未封成分片、不可 seek，留余量
                    var bcap = bs.durMs / 1000 - (bs.recording ? 3 : 0.2);
                    boff = Math.min(boff, Math.max(0, bcap));
                    loadSeg(bi, boff);
                    if (pb.zoom !== 24) { computeWin(true); }
                    return;
                }
            }
            seekTo(tMs);
        });
        (function () {
            var pv = $('pbVideo');
            pv.addEventListener('loadedmetadata', function () {
                var pb = state.pb;
                if (pb.pendingOffset != null) {
                    pv.currentTime = Math.min(pb.pendingOffset, Math.max(0, (pv.duration || 0) - 0.05));
                    pb.pendingOffset = null;
                }
            });
            pv.addEventListener('timeupdate', function () { updateCursor(); updateMeta(); autoFollow(); });
            pv.addEventListener('ended', pbNextSeg);
        })();

        document.querySelectorAll('.tab').forEach(function (t) {
            t.addEventListener('click', function () {
                document.querySelectorAll('.tab').forEach(function (x) { x.classList.remove('active'); });
                t.classList.add('active');
                var name = t.getAttribute('data-tab');
                $('tab-cameras').hidden = name !== 'cameras';
                $('tab-recordings').hidden = name !== 'recordings';
                $('tab-playback').hidden = name !== 'playback';
                $('tab-settings').hidden = name !== 'settings';
                if (name === 'recordings') loadRecordings();
                if (name === 'playback') enterPlayback();
                else { pausePb(); stopPbLive(); }
                if (name === 'settings') loadSettings();
            });
        });

        // 设备卡片按钮：一次性事件委托（轮询只改属性、不重建按钮，监听持续有效）
        $('deviceGrid').addEventListener('click', function (e) {
            var b = e.target.closest('button[data-act]');
            if (!b) return;
            var did = b.getAttribute('data-did');
            var act = b.getAttribute('data-act');
            if (act === 'preview') openPreview(did);
            else if (act === 'recstart') startRecord(did);
            else if (act === 'recstop') stopRecord(did);
        });

        $('previewClose').addEventListener('click', closePreview);
        $('playerClose').addEventListener('click', closePlayer);
        $('previewModal').addEventListener('click', function (e) { if (e.target === this) closePreview(); });
        $('playerModal').addEventListener('click', function (e) { if (e.target === this) closePlayer(); });
        $('btnCopyRtsp').addEventListener('click', function () { copyText($('previewRtsp').textContent); });

        var pv = $('previewVideo');
        pv.addEventListener('playing', function () { $('previewLoading').hidden = true; });
        pv.addEventListener('loadeddata', function () { $('previewLoading').hidden = true; });
    }

    /* ---------- 初始化 ---------- */
    async function init() {
        bind();
        // 一键解锁链接回跳：/?access_token=xxx，落盘令牌后清理地址栏（不依赖 Cookie，
        // iframe 中同样有效）
        var qs = new URLSearchParams(location.search);
        var accessToken = qs.get('access_token');
        if (accessToken) {
            setToken(accessToken);
            history.replaceState(null, '', location.pathname);
        }
        await refreshGate();
    }
    init();
})();
