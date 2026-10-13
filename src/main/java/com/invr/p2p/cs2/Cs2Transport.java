package com.invr.p2p.cs2;

import java.io.IOException;

/**
 * CS2 底层传输抽象，屏蔽 UDP（数据报）与 TCP（8 字节外层帧）差异。
 * 实现类保证 {@link #read(byte[])} 读出的都是一条完整的 CS2 内层消息
 * （UDP 为一个数据报，TCP 已剥掉 8 字节外层头）。
 */
public interface Cs2Transport {

    int read(byte[] buf) throws IOException;

    void write(byte[] data) throws IOException;

    void close() throws IOException;
}
