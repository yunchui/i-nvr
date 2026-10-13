package com.invr.cloud;

/**
 * 米家云交互通用异常。
 */
public class MiCloudException extends RuntimeException {

    public MiCloudException(String message) {
        super(message);
    }

    public MiCloudException(String message, Throwable cause) {
        super(message, cause);
    }
}
