/* iNvr 播放看门狗：直播流长期运行时检测“意外停滞 / 播放错误 / 画面冻结 / 墙钟延迟过大”，
   自动先 seek 追帧、失败再 remount 重新挂载；带失败计数与指数退避，达到上限即放弃，
   避免对无流/不可恢复设备无限重试。 */
(function () {
    'use strict';

    function start(video, remount, opts) {
        opts = opts || {};
        var checkMs = opts.checkMs || 2000;            // 检测周期
        var stallCount = opts.stallCount || 5;          // 连续 5 次（约 10s）currentTime 不推进即判定停滞
        var recoverMs = opts.recoverMs || 15000;        // 重新挂载后的恢复确认窗口
        var maxFails = opts.maxFails || 5;              // 连续失败上限，达到后放弃自动重试
        var backoffBase = opts.backoffBaseMs || 5000;   // 退避基数（第 n 次失败退避 base*n）
        var freezeMs = opts.freezeMs || 10000;          // 约 10s 无新帧呈现即判定画面冻结
        var latencyLimitSec = opts.latencyLimitSec || 15; // 墙钟延迟超过该值先 seek 追帧
        var seekConfirmMs = opts.seekConfirmMs || 5000; // seek 后确认窗口
        var maxSeekTries = opts.maxSeekTries || 2;      // 追帧最多尝试次数，仍失败则 remount

        var timer = null;
        var stopped = false;
        // watch（常规检测）| seeking（追帧确认）| healing（已重挂载，等待恢复确认）| backoff（退避中）
        var mode = 'watch';
        var lastCt = -1;
        var same = 0;
        var fails = 0;
        var heals = 0;
        var seekTries = 0;

        // ---- requestVideoFrameCallback：监测“实际呈现到合成层的帧”，可发现 ct 推进但画面定格 ----
        var rvfcSupported = typeof video.requestVideoFrameCallback === 'function';
        var rvfcId = null;
        var lastFrameWall = 0;
        var lastMediaTime = -1;

        function onFrame(now, metadata) {
            if (stopped) { return; }
            lastFrameWall = Date.now();
            lastMediaTime = metadata.mediaTime;
            rvfcId = video.requestVideoFrameCallback(onFrame);
        }
        if (rvfcSupported) {
            rvfcId = video.requestVideoFrameCallback(onFrame);
        }

        function readLatency() {
            if (typeof opts.getLatency !== 'function') { return null; }
            var v = opts.getLatency();
            return (v == null || !isFinite(v)) ? null : v;
        }

        function check() {
            if (stopped || mode !== 'watch') { return; }
            var err = video.error;
            var hasPicture = video.videoWidth > 0;
            var active = !video.paused && !video.seeking && video.readyState >= 2;

            // 非暂停、非 seek、已有数据，且 currentTime 与上一周期几乎相同 → 记一次停滞
            var stalled = active && Math.abs(video.currentTime - lastCt) < 0.05;
            same = stalled ? same + 1 : 0;
            lastCt = video.currentTime;

            // 画面冻结：rvfc 持续 freezeMs 无新帧回调（合成层停显），currentTime 可能仍在推进
            var frozen = rvfcSupported && active && lastFrameWall > 0 &&
                (Date.now() - lastFrameWall) >= freezeMs;

            // 已出过画面之后的错误（排除首帧解码失败）、画面冻结、或停滞累计达阈值 → 重新挂载
            if ((err && hasPicture) || frozen || same >= stallCount) {
                beginHeal();
                return;
            }

            // 墙钟延迟过大：先 seek 到 live 位置追帧，确认无效再 remount
            var latency = readLatency();
            if (latency != null && latency > latencyLimitSec) {
                beginSeek(latency);
            }
        }

        function beginSeek(latency) {
            if (typeof opts.seekLive !== 'function') {
                beginHeal(); // 调用方未提供 seek 能力，直接重挂载
                return;
            }
            mode = 'seeking';
            try { opts.seekLive(); } catch (e) { /* seek 抛错按失败处理，进入确认 */ }
            setTimeout(confirmSeek, seekConfirmMs);
        }

        function confirmSeek() {
            if (stopped) { return; }
            var latency = readLatency();
            var backToEdge = latency == null || latency <= latencyLimitSec;
            if (backToEdge) {
                seekTries = 0;
                lastCt = video.currentTime;
                same = 0;
                mode = 'watch';
                return;
            }
            seekTries++;
            if (seekTries >= maxSeekTries) {
                seekTries = 0;
                beginHeal(); // 连续追帧仍无法回到 live，重挂载兜底
                return;
            }
            // 再尝试一次 seek
            mode = 'seeking';
            try { opts.seekLive(); } catch (e) { /* ignore */ }
            setTimeout(confirmSeek, seekConfirmMs);
        }

        function beginHeal() {
            mode = 'healing';
            same = 0;
            heals++;
            try { remount(); } catch (e) { /* remount 抛错也按失败处理，进入确认流程 */ }
            setTimeout(confirmHeal, recoverMs);
        }

        function confirmHeal() {
            if (stopped) { return; }
            var healthy = !video.error && video.readyState >= 2 &&
                !video.paused && video.videoWidth > 0;
            if (healthy) {
                fails = 0;
                lastCt = video.currentTime;
                same = 0;
                mode = 'watch';
                return;
            }
            fails++;
            if (fails >= maxFails) {
                stop(); // 根本错误（如持续无流/不可解码）：放弃自动重试，交由页面兜底或人工处理
                return;
            }
            mode = 'backoff';
            setTimeout(function () {
                if (stopped) { return; }
                lastCt = video.currentTime;
                same = 0;
                mode = 'watch';
            }, backoffBase * fails);
        }

        function stop() {
            stopped = true;
            if (timer) { clearInterval(timer); timer = null; }
            if (rvfcSupported && rvfcId != null && typeof video.cancelVideoFrameCallback === 'function') {
                try { video.cancelVideoFrameCallback(rvfcId); } catch (e) { /* ignore */ }
            }
        }

        function getState() {
            return { mode: mode, stopped: stopped, fails: fails, heals: heals,
                seekTries: seekTries, currentTime: video.currentTime,
                mediaTime: lastMediaTime,
                frameAgeMs: lastFrameWall ? (Date.now() - lastFrameWall) : -1,
                latency: readLatency(),
                error: video.error ? video.error.code : 0 };
        }

        timer = setInterval(check, checkMs);
        return { stop: stop, getState: getState };
    }

    window.Watchdog = { start: start };
})();
