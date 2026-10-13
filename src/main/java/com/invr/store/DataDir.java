package com.invr.store;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 应用本地数据目录的统一解析入口。
 *
 * <p>该目录存放：主密钥 {@code master.key}、米家会话 {@code session.json}、访问密码
 * {@code access.json}、持续录像期望状态 {@code recordings.json}、CLI 图形验证码临时图片等。</p>
 *
 * <p>解析优先级：</p>
 * <ol>
 *   <li>JVM 系统属性 {@code -Dinvr.data.dir=<目录>}；</li>
 *   <li>环境变量 {@code INVR_DATA_DIR}；</li>
 *   <li>默认：<b>运行目录</b>（{@code user.dir}，即启动 {@code java} 时所在工作目录）下的
 *       {@code .invr/} —— 与录像目录 {@code recordings/} 一样让数据跟随程序目录，
 *       便于整目录拷贝 / 迁移、容器化挂载，且不污染用户家目录。</li>
 * </ol>
 *
 * <p>注意：IDE 与 {@code java -jar} 的运行目录通常都是工程 / jar 所在的启动目录；
 * 若以系统服务方式运行且工作目录不确定，请显式用 {@code -Dinvr.data.dir} 指定。</p>
 */
public final class DataDir {

    public static final String DIR_NAME = ".invr";

    private static volatile Path resolved;

    private DataDir() {
    }

    /** 返回数据目录（首次调用时解析并缓存；不主动创建，由各 Store 在写入时创建）。 */
    public static Path get() {
        Path d = resolved;
        if (d == null) {
            synchronized (DataDir.class) {
                d = resolved;
                if (d == null) {
                    d = resolve();
                    resolved = d;
                }
            }
        }
        return d;
    }

    /** 仅供测试：清除解析缓存，使下一次 {@link #get()} 重新求值。 */
    public static synchronized void resetForTest() {
        resolved = null;
    }

    private static Path resolve() {
        String override = System.getProperty("invr.data.dir");
        if (isBlank(override)) {
            override = System.getenv("INVR_DATA_DIR");
        }
        if (!isBlank(override)) {
            return Paths.get(override.trim());
        }
        return Paths.get(System.getProperty("user.dir"), DIR_NAME);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
