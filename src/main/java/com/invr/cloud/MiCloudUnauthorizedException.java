package com.invr.cloud;

/**
 * 业务 API 返回 code=401：serviceToken 失效，需要用 passToken 走 LoginWithToken 恢复。
 */
public class MiCloudUnauthorizedException extends MiCloudException {

    public MiCloudUnauthorizedException(String message) {
        super(message);
    }
}
