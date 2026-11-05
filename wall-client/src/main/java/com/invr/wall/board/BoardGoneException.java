package com.invr.wall.board;

/**
 * 看板返回 410：已过期、被禁用、删除或令牌轮换。客户端必须进入永久失效态，停止一切重试。
 */
public class BoardGoneException extends Exception {

    public BoardGoneException(String message) {
        super(message);
    }
}
