package com.invr.cloud;

/**
 * 登录过程中的人机交互回调（图形验证码 / 短信邮箱二次验证）。
 *
 * <p>CLI、Web 等不同前端各自实现；协议层不关心交互形态。
 */
public interface LoginInteraction {

    /**
     * 图形验证码。
     *
     * @param captchaImage 验证码图片字节（JPEG）
     * @return 用户输入的验证码
     */
    String askCaptcha(byte[] captchaImage);

    /**
     * 二次验证（短信或邮箱验证码）。
     *
     * @param kind          "Phone" 或 "Email"
     * @param maskedTarget  掩码后的手机号 / 邮箱，用于提示用户
     * @return 用户收到的验证码（ticket）
     */
    String askVerifyCode(String kind, String maskedTarget);
}
