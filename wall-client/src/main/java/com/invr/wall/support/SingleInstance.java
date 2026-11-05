package com.invr.wall.support;

import com.invr.wall.config.WallConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 单实例锁：数据目录下 wall.lock 文件锁，防止开机自启/重复点击拉起多个大屏进程。
 * 通道以静态引用持有，进程退出时由 OS 自动释放。
 */
public final class SingleInstance {

    private static final Logger log = LoggerFactory.getLogger(SingleInstance.class);
    private static FileChannel channel;

    private SingleInstance() {
    }

    /** 获得唯一实例锁返回 true；已有另一进程持锁返回 false。 */
    public static synchronized boolean tryLock() {
        try {
            Path dir = WallConfigStore.dataDir();
            Files.createDirectories(dir);
            Path lockFile = dir.resolve("wall.lock");
            channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                return false;
            }
            return true;
        } catch (OverlappingFileLockException e) {
            return false;
        } catch (Exception e) {
            // 锁失败不阻断启动（如文件系统不支持锁），仅告警
            log.warn("单实例锁获取失败，继续启动：{}", e.toString());
            return true;
        }
    }
}
