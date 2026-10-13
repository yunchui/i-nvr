package com.invr.cloud;

/**
 * 米家账号登录客户端。
 */
public interface MiCloudClient {

    /**
     * 账号密码登录。可能触发图形验证码或短信/邮箱二次验证，通过 interaction 交互完成。
     *
     * @param username    小米账号（手机号/邮箱/ID）
     * @param password    密码明文（内部做 MD5，不落地）
     * @param region      区域 cn/de/i2/ru/sg/us
     * @param interaction 验证码/二次验证回调
     * @return 登录会话
     */
    LoginSession login(String username, String password, String region, LoginInteraction interaction);

    /**
     * 使用已持久化的 userId + passToken 恢复会话（重启后免密登录）。
     */
    LoginSession loginWithToken(String userId, String passToken, String region);
}
