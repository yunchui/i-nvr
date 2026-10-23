package com.invr.rtsp;

import com.invr.hub.CameraConnector;
import com.invr.hub.StreamHub;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Java 内嵌 RTSP 下行服务（无独立进程）。仅支持 RTP-over-TCP interleaved。
 *
 * <p>每个播放器连接在 handler 中通过 {@link StreamHub#subscribe} 接入对应 DID 的唯一上游，
 * 因此"多个播放器 / 播放器+录像 / 播放器+HLS 预览"并发时，对同一摄像头仍只占用一条小米 CS2
 * 上游连接。设备查找与上游开启统一走 {@link CameraConnector}，便于在 Web 单例中动态装配。</p>
 *
 * <p>支持运行时通过 {@link #rebind(int)} 切换监听端口：先在新端口绑定成功，再关闭旧监听；
 * 若新端口绑定失败（被占用 / 无权限），保留旧服务继续运行并抛出异常。</p>
 */
public final class RtspServer {

    private static final Logger log = LoggerFactory.getLogger(RtspServer.class);

    private volatile int port;
    private final StreamHub hub;
    private final CameraConnector connector;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup bizGroup;
    private Channel serverChannel;

    public RtspServer(int port, StreamHub hub, CameraConnector connector) {
        this.port = port;
        this.hub = hub;
        this.connector = connector;
    }

    public int getPort() {
        return port;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        // 信令处理（DESCRIBE 可能等待参数集数秒）放独立业务线程池，绝不阻塞 IO eventloop 的 RTP 收发
        bizGroup = new DefaultEventExecutorGroup(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        serverChannel = bind(bossGroup, workerGroup, bizGroup, port);
        log.info("RTSP 下行服务已启动，端口 {}", port);
    }

    /** 组装并同步绑定监听端口，返回服务端 Channel。 */
    private Channel bind(EventLoopGroup boss, EventLoopGroup worker, EventExecutorGroup biz, int listenPort)
            throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new RtspRequestDecoder())
                                .addLast(biz, new RtspServerHandler(hub, connector));
                    }
                });
        return b.bind(listenPort).sync().channel();
    }

    /**
     * 运行时切换监听端口。
     *
     * <p>先在新端口完成绑定（旧服务期间仍正常工作）；新端口绑定成功后再关闭旧监听。
     * 新端口绑定失败则释放临时资源、保留旧服务，并抛出 {@link IOException}。</p>
     */
    public synchronized void rebind(int newPort) throws IOException {
        int oldPort = this.port;
        if (newPort == oldPort) {
            log.info("RTSP 端口未变化（{}），无需切换", newPort);
            return;
        }
        EventLoopGroup nb = new NioEventLoopGroup(1);
        EventLoopGroup nw = new NioEventLoopGroup();
        EventExecutorGroup nbiz = new DefaultEventExecutorGroup(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        Channel newChannel;
        try {
            newChannel = bind(nb, nw, nbiz, newPort);
        } catch (Exception bindErr) {
            // 新端口绑定失败：释放刚创建的资源，旧服务不受影响、继续运行
            nbiz.shutdownGracefully();
            nb.shutdownGracefully();
            nw.shutdownGracefully();
            throw new IOException("端口 " + newPort + " 绑定失败（可能被占用或无权限），RTSP 仍在原端口 "
                    + oldPort + "：" + bindErr.getMessage(), bindErr);
        }
        // 新端口就绪，再关闭旧监听与线程组（已连的播放会话会断开，需用新端口重连）
        Channel old = serverChannel;
        EventLoopGroup ob = bossGroup;
        EventLoopGroup ow = workerGroup;
        EventExecutorGroup obiz = bizGroup;
        serverChannel = newChannel;
        bossGroup = nb;
        workerGroup = nw;
        bizGroup = nbiz;
        this.port = newPort;
        closeQuietly(old, ob, ow, obiz);
        log.info("RTSP 监听端口已由 {} 切换为 {}（旧播放连接已断开，请用新端口重连）", oldPort, newPort);
    }

    public void stop() {
        closeQuietly(serverChannel, bossGroup, workerGroup, bizGroup);
        serverChannel = null;
    }

    private void closeQuietly(Channel ch, EventLoopGroup boss, EventLoopGroup worker, EventExecutorGroup biz) {
        if (ch != null) {
            try {
                ch.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (biz != null) {
            biz.shutdownGracefully();
        }
        if (boss != null) {
            boss.shutdownGracefully();
        }
        if (worker != null) {
            worker.shutdownGracefully();
        }
    }
}
