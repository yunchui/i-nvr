package com.invr.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invr.model.CameraDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 摄像头 CS2 直连 IP 解析与历史可用 IP 缓存。
 *
 * <p>背景：取流目标 host 来自米家云端设备列表的 {@code localip}。实测该字段偶发返回
 * {@code 0.0.0.0}（设备在云端仍显示“在线”、协商也成功，但没有上报局域网 IP，常见于设备
 * 网络重连 / 云通道刚恢复时），此时直接拿 0.0.0.0 打洞必然失败。</p>
 *
 * <p>解析优先级：</p>
 * <ol>
 *   <li>系统属性手动覆盖：{@code -Dinvr.host.<did>=<IP>}（最高优先级，用于 IP 变更/排障）；</li>
 *   <li>云端返回的 {@code localip} 有效：采用，并写入 {@code hosts.json} 记忆；</li>
 *   <li>云端无效（0.0.0.0/空）：回退到 {@code hosts.json} 中该设备最后一次的可用 IP；</li>
 *   <li>都没有：返回 {@code null}，由上层报错并提示手动指定。</li>
 * </ol>
 *
 * 缓存文件 {@code hosts.json} 位于数据目录（默认运行目录 {@code .invr/}，见 {@link DataDir}）。
 */
public class HostResolver {

    private static final Logger log = LoggerFactory.getLogger(HostResolver.class);
    private static final String FILE_NAME = "hosts.json";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public HostResolver() {
        this(DataDir.get());
    }

    public HostResolver(Path dataDir) {
        this.file = dataDir.resolve(FILE_NAME);
    }

    /**
     * 解析某台设备的 CS2 直连 host；无法确定时返回 {@code null}。
     */
    public String resolve(CameraDevice device) {
        String did = device.did();

        // 1. 系统属性手动覆盖
        String manual = System.getProperty("invr.host." + did);
        if (isValid(manual)) {
            log.info("设备 {} 使用手动指定的局域网 IP {}", did, manual.trim());
            return manual.trim();
        }

        // 2. 云端 localip 有效：采用并记忆
        String cloud = device.localIp();
        if (isValid(cloud)) {
            remember(did, cloud.trim());
            return cloud.trim();
        }

        // 3. 云端未给出有效 IP：回退历史缓存
        String cached = load().get(did);
        if (isValid(cached)) {
            log.warn("设备 {} 云端返回局域网 IP 为 [{}]，回退使用历史可用 IP {}", did, cloud, cached);
            return cached;
        }
        return null;
    }

    /** IP 是否可用于 CS2 直连：非空、非 0.0.0.0 等占位值。 */
    static boolean isValid(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String s = ip.trim();
        return !s.equals("0.0.0.0") && !s.equals("::") && !s.startsWith("0.0.0.0");
    }

    /** 读取全部历史 IP（{did: ip}）；文件缺失/损坏返回空 Map，不抛异常。 */
    private synchronized Map<String, String> load() {
        try {
            if (Files.exists(file)) {
                Map<String, String> m = mapper.readValue(
                        Files.readString(file, StandardCharsets.UTF_8),
                        new TypeReference<LinkedHashMap<String, String>>() {});
                if (m != null) {
                    return m;
                }
            }
        } catch (Exception e) {
            log.warn("读取历史 IP 缓存 {} 失败：{}", file, e.toString());
        }
        return new LinkedHashMap<>();
    }

    /** 记忆某台设备的有效 IP（临时文件 + 原子改名，避免写坏）。 */
    private synchronized void remember(String did, String ip) {
        Map<String, String> m = load();
        if (ip.equals(m.get(did))) {
            return;
        }
        m.put(did, ip);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m),
                    StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFail) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("写入历史 IP 缓存 {} 失败：{}", file, e.toString());
        }
    }
}
