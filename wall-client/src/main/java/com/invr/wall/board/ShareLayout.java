package com.invr.wall.board;

/**
 * 共享看板布局常量，与服务端 ShareBoard 的 LAYOUT_* 取值严格一致。
 */
public final class ShareLayout {

    /** 2×2 四宫格。 */
    public static final String GRID4 = "grid4";
    /** 3×3 九宫格。 */
    public static final String GRID9 = "grid9";
    /** 3×3 轨道上 1 大（2×2 跨）+ 5 小。 */
    public static final String GRID6 = "grid6";

    private ShareLayout() {
    }

    /** 布局对应的网格轨道数（grid4 为 2，其余为 3）。 */
    public static int trackCount(String layout) {
        return GRID4.equals(layout) ? 2 : 3;
    }
}
