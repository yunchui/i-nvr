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

/**
 * Java 内嵌 RTSP 下行服务（无独立进程）。仅支持 RTP-over-TCP interleaved。
 *
 * <p>每个播放器连接在 handler 中通过 {@link StreamHub#subscribe} 接入对应 DID 的唯一上游，
 * 因此"多个播放器 / 播放器+录像 / 播放器+HLS 预览"并发时，对同一摄像头仍只占用一条小米 CS2
 * 上游连接。设备查找与上游开启统一走 {@link CameraConnector}，便于在 Web 单例中动态装配。</p>
 */
public final class RtspServer {

    private static final Logger log = LoggerFactory.getLogger(RtspServer.class);

    private final int port;
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

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        // 信令处理（DESCRIBE 可能等待参数集数秒）放独立业务线程池，绝不阻塞 IO eventloop 的 RTP 收发
        bizGroup = new DefaultEventExecutorGroup(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new RtspRequestDecoder())
                                .addLast(bizGroup, new RtspServerHandler(hub, connector));
                    }
                });
        serverChannel = b.bind(port).sync().channel();
        log.info("RTSP 下行服务已启动，端口 {}", port);
    }

    public void stop() {
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (bizGroup != null) {
            bizGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
