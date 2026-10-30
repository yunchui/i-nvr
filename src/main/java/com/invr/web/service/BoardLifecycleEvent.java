package com.invr.web.service;

/**
 * 共享看板生命周期事件：看板被删除、停用或共享令牌轮换时发布，
 * 用于通知事件服务关闭其 SSE 连接、清除事件态（解耦，避免服务间循环依赖）。
 */
public class BoardLifecycleEvent {

    private final String boardId;

    public BoardLifecycleEvent(String boardId) {
        this.boardId = boardId;
    }

    public String getBoardId() {
        return boardId;
    }
}
