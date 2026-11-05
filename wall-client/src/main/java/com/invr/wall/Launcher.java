package com.invr.wall;

import com.invr.wall.support.SingleInstance;
import com.invr.wall.ui.WallApplication;
import javafx.application.Application;

/**
 * 可执行 fat jar 的 Main-Class。
 *
 * <p>刻意不直接继承 {@link Application}：classpath（非模块路径）运行 JavaFX 时，
 * 启动类若为 Application 子类会触发 JavaFX 运行时检测失败；由普通类转发 launch 可规避。</p>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        if (!SingleInstance.tryLock()) {
            System.err.println("iNvr 大屏客户端已在运行，重复启动被拒绝。");
            System.exit(2);
        }
        Application.launch(WallApplication.class, args);
    }
}
