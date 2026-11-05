package com.invr.wall.support;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 全局 Jackson 单例：容忍服务端后续新增字段（反序列化未知属性不失败）。
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }
}
