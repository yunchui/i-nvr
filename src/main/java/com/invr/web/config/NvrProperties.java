package com.invr.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * iNvr Web 服务配置（application.yml 中 {@code invr.*}）。
 */
@ConfigurationProperties(prefix = "invr")
public class NvrProperties {

    private int rtspPort = 8554;
    private String recordingDir = "recordings";
    private long recordSegmentSec = 120;
    /** 录像保留天数：0 表示永久保留，不自动清理；>0 时超出该天数的分段自动删除。 */
    private int recordRetentionDays = 0;
    private final Hls hls = new Hls();
    private final Share share = new Share();

    public int getRtspPort() {
        return rtspPort;
    }

    public void setRtspPort(int rtspPort) {
        this.rtspPort = rtspPort;
    }

    public String getRecordingDir() {
        return recordingDir;
    }

    public void setRecordingDir(String recordingDir) {
        this.recordingDir = recordingDir;
    }

    public long getRecordSegmentSec() {
        return recordSegmentSec;
    }

    public void setRecordSegmentSec(long recordSegmentSec) {
        this.recordSegmentSec = recordSegmentSec;
    }

    public int getRecordRetentionDays() {
        return recordRetentionDays;
    }

    public void setRecordRetentionDays(int recordRetentionDays) {
        this.recordRetentionDays = recordRetentionDays;
    }

    public Hls getHls() {
        return hls;
    }

    public Share getShare() {
        return share;
    }

    public static class Hls {
        private int targetSec = 2;
        private int window = 6;
        private int idleSec = 25;
        private boolean audio = true;

        public int getTargetSec() {
            return targetSec;
        }

        public void setTargetSec(int targetSec) {
            this.targetSec = targetSec;
        }

        public int getWindow() {
            return window;
        }

        public void setWindow(int window) {
            this.window = window;
        }

        public int getIdleSec() {
            return idleSec;
        }

        public void setIdleSec(int idleSec) {
            this.idleSec = idleSec;
        }

        public boolean isAudio() {
            return audio;
        }

        public void setAudio(boolean audio) {
            this.audio = audio;
        }
    }

    /** 共享看板相关配置（application.yml 中 invr.share.*）。 */
    public static class Share {
        /** 事件画面持续秒数，到期广播 end。 */
        private long eventDurationSec = 60;
        /** SSE 心跳周期（秒）。 */
        private long sseHeartbeatSec = 15;
        /** 展示页配置轮询周期（秒）。 */
        private long configPollSec = 60;
        /** 大屏兜底重载周期（秒），0 关闭。 */
        private long pageReloadSec = 3600;

        public long getEventDurationSec() {
            return eventDurationSec;
        }

        public void setEventDurationSec(long eventDurationSec) {
            this.eventDurationSec = eventDurationSec;
        }

        public long getSseHeartbeatSec() {
            return sseHeartbeatSec;
        }

        public void setSseHeartbeatSec(long sseHeartbeatSec) {
            this.sseHeartbeatSec = sseHeartbeatSec;
        }

        public long getConfigPollSec() {
            return configPollSec;
        }

        public void setConfigPollSec(long configPollSec) {
            this.configPollSec = configPollSec;
        }

        public long getPageReloadSec() {
            return pageReloadSec;
        }

        public void setPageReloadSec(long pageReloadSec) {
            this.pageReloadSec = pageReloadSec;
        }
    }
}
