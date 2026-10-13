package com.invr.cloud;

/**
 * 登录完成后的会话凭据。
 *
 * <p>持久化时必须加密（见 store.SessionStore）；日志中禁止打印 passToken / ssecurity。
 *
 * @param userId       小米用户 ID（形如 V1:xxxx）
 * @param cUserId      cUserId
 * @param passToken    长期令牌，用于 LoginWithToken 恢复会话（敏感）
 * @param serviceToken 业务 API 会话令牌（敏感）
 * @param ssecurity    业务 API 签名主密钥（base64，敏感）
 * @param region       区域 cn/de/i2/ru/sg/us
 */
public record LoginSession(
        String userId,
        String cUserId,
        String passToken,
        String serviceToken,
        String ssecurity,
        String region
) {
    /** 业务 API 请求 Cookie 头。 */
    public String cookieString() {
        return "userId=" + userId + "; cUserId=" + cUserId + "; serviceToken=" + serviceToken;
    }
}
