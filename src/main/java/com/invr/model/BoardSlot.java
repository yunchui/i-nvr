package com.invr.model;

/**
 * 共享看板槽位坐标（CSS Grid 网格线定位）。
 *
 * @param key      槽位标识（grid6 主格为 "BIG"，其余为序号字符串）
 * @param colStart 起始列线（从 1 开始）
 * @param rowStart 起始行线（从 1 开始）
 * @param colSpan 跨越列数
 * @param rowSpan 跨越行数
 */
public record BoardSlot(
        String key,
        int colStart,
        int rowStart,
        int colSpan,
        int rowSpan
) {
}
