package com.invr.web.service;

import com.invr.model.BoardSlot;
import com.invr.model.ShareBoard;

import java.util.List;

/**
 * 三种布局的槽位坐标表（后端权威定义，board 只读接口据此下发，前端按坐标渲染 CSS Grid）。
 *
 * <p>grid4 基于 2×2 轨道；grid9 与 grid6 基于 3×3 轨道，grid6 大格跨 2×2。</p>
 */
public final class BoardLayouts {

    private BoardLayouts() {
    }

    public static List<BoardSlot> slots(String layout) {
        return switch (layout) {
            case ShareBoard.LAYOUT_GRID4 -> List.of(
                    new BoardSlot("1", 1, 1, 1, 1),
                    new BoardSlot("2", 2, 1, 1, 1),
                    new BoardSlot("3", 1, 2, 1, 1),
                    new BoardSlot("4", 2, 2, 1, 1));
            case ShareBoard.LAYOUT_GRID9 -> List.of(
                    new BoardSlot("1", 1, 1, 1, 1),
                    new BoardSlot("2", 2, 1, 1, 1),
                    new BoardSlot("3", 3, 1, 1, 1),
                    new BoardSlot("4", 1, 2, 1, 1),
                    new BoardSlot("5", 2, 2, 1, 1),
                    new BoardSlot("6", 3, 2, 1, 1),
                    new BoardSlot("7", 1, 3, 1, 1),
                    new BoardSlot("8", 2, 3, 1, 1),
                    new BoardSlot("9", 3, 3, 1, 1));
            case ShareBoard.LAYOUT_GRID6 -> List.of(
                    new BoardSlot("BIG", 1, 1, 2, 2),
                    new BoardSlot("S1", 3, 1, 1, 1),
                    new BoardSlot("S2", 3, 2, 1, 1),
                    new BoardSlot("S3", 1, 3, 1, 1),
                    new BoardSlot("S4", 2, 3, 1, 1),
                    new BoardSlot("S5", 3, 3, 1, 1));
            default -> throw new IllegalArgumentException("非法布局：" + layout);
        };
    }
}
