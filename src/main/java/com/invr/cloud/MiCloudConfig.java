package com.invr.cloud;

import java.util.Locale;

/**
 * 米家云端点常量与区域配置。
 */
public final class MiCloudConfig {

    /** 米家 IoT 业务固定 sid。 */
    public static final String SID = "xiaomiio";

    public static final String ACCOUNT_BASE = "https://account.xiaomi.com";
    public static final String ACCOUNT_HOST = "account.xiaomi.com";

    public static final String SERVICE_LOGIN =
            ACCOUNT_BASE + "/pass/serviceLogin?_json=true&sid=" + SID;
    public static final String AUTH2_URL =
            ACCOUNT_BASE + "/pass/serviceLoginAuth2";

    private static final String API_BASE_CN = "https://api.io.mi.com/app";

    private MiCloudConfig() {
    }

    /**
     * 业务 API 基址。cn 为中国区；de/i2/ru/sg/us 为海外区域。
     */
    public static String apiBase(String region) {
        if (region == null || region.isBlank() || region.equalsIgnoreCase("cn")) {
            return API_BASE_CN;
        }
        return "https://" + region.toLowerCase(Locale.ROOT) + ".api.io.mi.com/app";
    }
}
