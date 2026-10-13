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
    private final Hls hls = new Hls();

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

    public Hls getHls() {
        return hls;
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
}
