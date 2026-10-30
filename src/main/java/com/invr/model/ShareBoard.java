package com.invr.model;

import java.util.List;

/**
 * 共享看板：管理员配置的多宫格免鉴权展示单元。
 *
 * <p>描述一个布局（{@link #layout}）与一组有序摄像头（{@link #cameras}，按槽位顺序映射，
 * grid6 第 1 路即大格默认主位），并持有两条相互独立的凭据：进共享 URL 的 {@link #shareToken}
 * 与外部事件系统调用 Webhook 的 {@link #webhookSecret}。</p>
 *
 * <p>公共字段 POJO，由 Jackson 直接序列化到 {@code boards.json}，风格对齐
 * {@code AccessKeyStore.Record}；不使用 JPA。</p>
 */
public class ShareBoard {

    /** 4 宫格（2×2，4 槽）。 */
    public static final String LAYOUT_GRID4 = "grid4";
    /** 9 宫格（3×3，9 槽）。 */
    public static final String LAYOUT_GRID9 = "grid9";
    /** 6 宫格（1+5，3×3 轨道上大格跨 2×2）。 */
    public static final String LAYOUT_GRID6 = "grid6";

    /** 看板主键（内部引用，不进共享 URL）。 */
    public String id;
    /** 看板名称。 */
    public String name;
    /** 布局类型，取值为上述 LAYOUT_* 常量。 */
    public String layout;
    /** 有序 did 列表，长度须与布局槽位数一致且不重复。 */
    public List<String> cameras;
    /** 共享令牌（256 bit 随机 base64url），出现在共享 URL 路径中。 */
    public String shareToken;
    /** Webhook 密钥（256 bit 随机 base64url），请求头 X-Share-Secret 携带。 */
    public String webhookSecret;
    /** 过期时间戳（毫秒）；null 表示永久有效。 */
    public Long expireAt;
    /** 启用开关；false 等价于临时吊销。 */
    public boolean enabled = true;
    /** 创建时间戳。 */
    public long createdAt;
    /** 最近更新时间戳。 */
    public long updatedAt;
}
