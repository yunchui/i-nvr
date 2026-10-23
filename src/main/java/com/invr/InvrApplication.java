package com.invr;

import com.invr.cloud.DeviceDiscovery;
import com.invr.cloud.LoginInteraction;
import com.invr.cloud.LoginSession;
import com.invr.cloud.MiCloudApi;
import com.invr.cloud.MiCloudClient;
import com.invr.cloud.MiCloudClientImpl;
import com.invr.cloud.MiCloudException;
import com.invr.cloud.MiCloudUnauthorizedException;
import com.invr.cloud.CloudMissOpener;
import com.invr.cloud.VendorNegotiator;
import com.invr.hub.AnnexBFileSink;
import com.invr.hub.CameraConnector;
import com.invr.hub.MissOpener;
import com.invr.hub.StreamHub;
import com.invr.miss.MissClient;
import com.invr.miss.MissCommands;
import com.invr.miss.MissPacket;
import com.invr.miss.NalUnits;
import com.invr.model.CameraDevice;
import com.invr.record.Mp4Remuxer;
import com.invr.record.RecordingSink;
import com.invr.rtsp.RtspServer;
import com.invr.model.P2pConnectParams;
import com.invr.store.DataDir;
import com.invr.store.HostResolver;
import com.invr.store.SessionStore;

import java.io.Console;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * iNvr 命令行入口（M1）。
 *
 * <pre>
 *   invr login &lt;账号&gt; [密码]      登录米家账号（验证码/2FA 按提示交互；密码省略则安全输入）
 *   invr devices                 列出账号下摄像头
 *   invr negotiate &lt;did&gt;         miss_get_vendor 协商，打印 CS2 连接参数（私钥不打印）
 *   invr whoami                  查看当前会话
 *   invr logout                  清除本地会话
 * </pre>
 * 区域通过环境变量 INVR_REGION 设置（cn/de/i2/ru/sg/us），默认 cn。
 */
public class InvrApplication {

    private static final Scanner SCANNER;

    static {
        // Windows 控制台默认 GBK：按控制台实际编码输出/输入，避免中文乱码；
        // 管道/重定向（无 console）时统一 UTF-8。
        Charset consoleCharset = System.console() != null
                ? System.console().charset()
                : StandardCharsets.UTF_8;
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, consoleCharset));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, consoleCharset));
        SCANNER = new Scanner(System.in, consoleCharset);
    }

    private final SessionStore sessionStore = new SessionStore();

    public static void main(String[] args) {
        InvrApplication app = new InvrApplication();
        try {
            if (args.length == 0 || "help".equalsIgnoreCase(args[0]) || "-h".equals(args[0])) {
                usage();
                return;
            }
            switch (args[0]) {
                case "login" -> app.doLogin(args);
                case "devices" -> app.doListDevices();
                case "negotiate" -> app.doNegotiate(args);
                case "cs2url" -> app.doCs2Url(args);
                case "mockcs2" -> app.doMockCs2(args);
                case "streamurl" -> app.doStreamUrl(args);
                case "stream" -> app.doStream(args);
                case "record" -> app.doRecord(args);
                case "fanout" -> app.doFanout(args);
                case "serve" -> app.doServe(args);
                case "remux" -> app.doRemux(args);
                case "whoami" -> app.doWhoami();
                case "logout" -> app.doLogout();
                default -> {
                    System.out.println("未知命令：" + args[0]);
                    usage();
                    System.exit(2);
                }
            }
        } catch (MiCloudException e) {
            System.err.println("[错误] " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[异常] " + e);
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("""
                iNvr - 小米摄像头 NVR（M1：云端认证与设备发现；M2：CS2/MISS 取流）

                用法:
                  invr login <账号> [密码]      登录米家账号（验证码/2FA 按提示交互）
                  invr devices                 列出账号下摄像头
                  invr negotiate <did>         miss 协商 CS2 连接参数
                  invr stream <did> [秒数] [输出文件]   连接摄像头并把视频裸流落盘（默认30秒）
                  invr record <did> [秒数] [分段秒] [目录]  MP4分段录像（含音频，默认60秒/120秒分段）
                  invr fanout <did> [秒数]     单通道扇出自检（录像+裸流两个消费者复用一条上游）
                  invr serve [端口=8554]       常驻 RTSP 下行服务（rtsp://主机:端口/<did>）
                  invr whoami                  查看当前会话
                  invr logout                  清除本地会话

                示例:
                  invr stream 1190193414              取流 30 秒，写入 stream-<did>.h264/.h265
                  invr stream 1190193414 60 clip.h264 取流 60 秒并指定输出文件

                环境变量:
                  INVR_REGION   区域 cn(默认)/de/i2/ru/sg/us
                """);
    }

    private static String region() {
        String r = System.getenv("INVR_REGION");
        return r == null || r.isBlank() ? "cn" : r.trim();
    }

    // ------------------------------------------------------------------
    // login
    // ------------------------------------------------------------------

    private void doLogin(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("用法：invr login <账号> [密码]");
            System.exit(2);
        }
        String username = args[1];
        String password = args.length >= 3 ? args[2] : readPassword();
        if (password == null || password.isBlank()) {
            throw new MiCloudException("密码不能为空");
        }

        MiCloudClient client = new MiCloudClientImpl();
        LoginSession session = client.login(username, password, region(), new CliInteraction());
        sessionStore.save(session);
        System.out.println("[成功] 登录完成，会话已加密保存");
        System.out.println("       userId = " + session.userId());
        System.out.println("       region = " + session.region());
    }

    private static String readPassword() {
        Console console = System.console();
        if (console != null) {
            char[] pwd = console.readPassword("请输入米家账号密码（输入不回显）: ");
            return new String(pwd);
        }
        System.out.print("请输入米家账号密码: ");
        return SCANNER.nextLine();
    }

    // ------------------------------------------------------------------
    // devices
    // ------------------------------------------------------------------

    private void doListDevices() {
        LoginSession session = requireSession();
        List<CameraDevice> cameras = listCamerasWithRecovery(session);
        if (cameras.isEmpty()) {
            System.out.println("账号下未发现在支持列表中的摄像头。");
            return;
        }
        System.out.printf("发现 %d 台摄像头：%n", cameras.size());
        System.out.printf("%-22s %-26s %-16s %-8s %s%n", "DID", "MODEL", "LOCAL_IP", "ONLINE", "NAME");
        System.out.println("-".repeat(98));
        for (CameraDevice c : cameras) {
            System.out.printf("%-22s %-26s %-16s %-8s %s%n",
                    c.did(), c.model(), c.localIp(), c.online() ? "在线" : "离线", c.name());
        }
    }

    // ------------------------------------------------------------------
    // negotiate
    // ------------------------------------------------------------------

    private void doNegotiate(String[] args) {
        if (args.length < 2) {
            System.err.println("用法：invr negotiate <did>");
            System.exit(2);
        }
        String did = args[1];
        LoginSession session = requireSession();

        // 先用设备列表补全 model/localip（协商本身只需要 did）
        List<CameraDevice> cameras = listCamerasWithRecovery(session);
        CameraDevice device = cameras.stream().filter(c -> c.did().equals(did)).findFirst()
                .orElse(new CameraDevice(did, "", "", "", "", false));

        MiCloudApi api = new MiCloudApi(session);
        VendorNegotiator negotiator = new VendorNegotiator(api);
        P2pConnectParams params = negotiator.negotiate(device);

        System.out.println("[成功] miss_get_vendor 协商完成（CS2）");
        System.out.println("  did        = " + params.did());
        System.out.println("  model      = " + params.model());
        System.out.println("  host       = " + params.host());
        System.out.println("  vendor     = " + params.vendor() + " (cs2)");
        System.out.println("  p2p_id     = " + params.p2pId());
        System.out.println("  device_pub = " + mask(params.devicePublicHex()));
        System.out.println("  sign       = " + mask(params.sign()));
        System.out.println("  client_pub = " + mask(params.clientPublicHex()));
        System.out.println("  client_priv= 【敏感，仅存内存，不打印】");
    }

    // ------------------------------------------------------------------
    // cs2url（调试：生成 go2rtc 直连 URL 用于协议对拍，含一次性私钥，勿外传）
    // ------------------------------------------------------------------

    private void doCs2Url(String[] args) {
        if (args.length < 2) {
            System.err.println("用法：invr cs2url <did>");
            System.exit(2);
        }
        String did = args[1];
        LoginSession session = requireSession();
        List<CameraDevice> cameras = listCamerasWithRecovery(session);
        CameraDevice device = cameras.stream().filter(c -> c.did().equals(did)).findFirst()
                .orElseThrow(() -> new MiCloudException("设备列表中未找到 did=" + did));
        MiCloudApi api = new MiCloudApi(session);
        P2pConnectParams p = new VendorNegotiator(api).negotiate(device);

        String url = "xiaomi://" + p.host() + "/?vendor=cs2"
                + "&model=" + urlEncode(p.model())
                + "&device_public=" + p.devicePublicHex()
                + "&client_public=" + p.clientPublicHex()
                + "&client_private=" + p.clientPrivateHex()
                + "&sign=" + p.sign();
        System.out.println(url);
        System.err.println("[注意] 该 URL 含本次协商私钥，仅限本机 go2rtc 对拍使用，请勿外传；参数一次性，失效后重新生成。");
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // remux（M3 原型：H.265 裸流 -> MP4，进程内 FFmpeg 流拷贝）
    // ------------------------------------------------------------------

    private void doRemux(String[] args) {
        if (args.length < 3) {
            System.err.println("用法：invr remux <输入.h265/.h264> <输出.mp4>");
            System.exit(2);
        }
        try {
            Mp4Remuxer.remuxFile(args[1], args[2]);
        } catch (Exception e) {
            System.err.println("[异常] " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // mockcs2（调试：本地假摄像头，dump go2rtc 实际发出的 CS2 帧）
    // ------------------------------------------------------------------

    private void doMockCs2(String[] args) {
        int seconds = args.length >= 2 ? Integer.parseInt(args[1]) : 30;
        try {
            com.invr.p2p.cs2.MockCs2Server.run(seconds);
        } catch (Exception e) {
            System.err.println("[异常] " + e);
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------------------
    // streamurl（调试：用固定 go2rtc 风格 URL 的密钥参数直连，跳过云端协商）
    // ------------------------------------------------------------------

    private void doStreamUrl(String[] args) {
        if (args.length < 2) {
            System.err.println("用法：invr streamurl \"<xiaomi://host/?vendor=cs2&model=..&device_public=..&client_public=..&client_private=..&sign=..>\" [秒数]");
            System.exit(2);
        }
        String url = args[1];
        int seconds = args.length >= 3 ? Integer.parseInt(args[2]) : 30;
        try {
            java.net.URI u = java.net.URI.create(url);
            String host = u.getHost();
            Map<String, String> q = parseQuery(u.getRawQuery());
            P2pConnectParams p = new P2pConnectParams(
                    "urlcam", q.get("model"), host, 4, "",
                    q.get("client_public"), q.get("client_private"),
                    q.get("device_public"), q.get("sign"));
            System.out.println("streamurl 直连（经 StreamHub，断流自动重连）" + p.model() + " @ " + host);

            // 固定参数 opener：每次（重）连接都重新 connect + VideoStart（不重新协商，密钥来自 URL）
            MissOpener opener = () -> {
                MissClient c = MissClient.connect(p, "");
                boolean ok = false;
                try {
                    c.startMedia("", "hd", "1");
                    ok = true;
                    return c;
                } finally {
                    if (!ok) {
                        c.close();
                    }
                }
            };

            StreamHub hub = new StreamHub();
            AnnexBFileSink sink = new AnnexBFileSink("urlcam", null);
            CountDownLatch stopLatch = new CountDownLatch(1);
            AtomicBoolean cleaned = new AtomicBoolean(false);
            Runnable cleanup = () -> {
                if (!cleaned.compareAndSet(false, true)) {
                    return;
                }
                hub.unsubscribe("urlcam", sink);
                try {
                    sink.close();
                } catch (Exception ignore) {
                    // 关闭异常忽略
                }
                hub.shutdown();
                printStreamStats(sink);
                stopLatch.countDown();
            };
            Thread hook = new Thread(cleanup, "invr-stream-cleanup");
            Runtime.getRuntime().addShutdownHook(hook);

            hub.subscribe("urlcam", p.model(), opener, sink);
            try {
                if (seconds > 0) {
                    stopLatch.await(seconds, TimeUnit.SECONDS);
                } else {
                    stopLatch.await();
                }
            } finally {
                cleanup.run();
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ignore) {
                    // JVM 已在关闭中
                }
            }
        } catch (Exception e) {
            System.err.println("[异常] " + e);
            e.printStackTrace();
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) {
            return m;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                String k = urlDecode(pair.substring(0, i));
                String v = urlDecode(pair.substring(i + 1));
                m.put(k, v);
            }
        }
        return m;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------------
    // stream（M2：CS2/MISS 取流，视频裸流落盘）
    // ------------------------------------------------------------------

    private void doStream(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：invr stream <did> [秒数] [输出文件]（秒数 0 表示持续取流，用于长稳/重连测试）");
            System.exit(2);
        }
        String did = args[1];
        int seconds = 30;
        if (args.length >= 3) {
            try {
                seconds = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("秒数必须是整数：" + args[2]);
                System.exit(2);
            }
        }
        String outArg = args.length >= 4 ? args[3] : null;

        LoginSession session = requireSession();
        List<CameraDevice> cameras = listCamerasWithRecovery(session);
        CameraDevice device = cameras.stream().filter(c -> c.did().equals(did)).findFirst()
                .orElseThrow(() -> new MiCloudException(
                        "设备列表中未找到 did=" + did + "（取流需要设备局域网 IP，请确认设备在线且已绑定）"));

        // 实际 CS2 连接 host：云端 localip 有效用云端，否则回退历史可用 IP（HostResolver）
        String connectHost = new HostResolver().resolve(device);
        if (connectHost == null || connectHost.isBlank()) {
            connectHost = device.localIp();
        }
        System.out.println("正在连接 " + device.model() + " @ " + connectHost
                + "（云端 localip=" + device.localIp() + "）...");
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(connectHost);
            if (addr.isReachable(3_000)) {
                System.out.println("网络可达性检测：" + connectHost + " 可达");
            } else {
                System.out.println("[警告] ping " + connectHost + " 无回应（部分设备禁 ICMP，仍尝试握手；"
                        + "若持续握手超时，请检查电脑与摄像头是否同一网段、Windows 防火墙是否放行 Java）");
            }
        } catch (Exception e) {
            System.out.println("[警告] 可达性检测失败：" + e.getMessage());
        }

        AtomicReference<LoginSession> sessionRef = new AtomicReference<>(session);
        StreamHub hub = new StreamHub();
        AnnexBFileSink sink = new AnnexBFileSink(did, outArg);
        CloudMissOpener opener = new CloudMissOpener(sessionStore, sessionRef, device, "", "hd", "1");

        CountDownLatch stopLatch = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            hub.unsubscribe(did, sink);
            try {
                sink.close();
            } catch (Exception ignore) {
                // 关闭异常忽略
            }
            hub.shutdown();
            printStreamStats(sink);
            stopLatch.countDown();
        };
        Thread hook = new Thread(cleanup, "invr-stream-cleanup");
        Runtime.getRuntime().addShutdownHook(hook);

        System.out.println("通过 StreamHub 订阅（同一设备全局仅一条上游 CS2，断流自动重连）...");
        hub.subscribe(did, device.model(), opener, sink);

        try {
            if (seconds > 0) {
                stopLatch.await(seconds, TimeUnit.SECONDS);
            } else {
                System.out.println("持续取流中（Ctrl+C 结束并打印统计）...");
                stopLatch.await();
            }
        } finally {
            cleanup.run();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignore) {
                // JVM 已在关闭中
            }
        }
    }

    private static void printStreamStats(AnnexBFileSink sink) {
        System.out.println("================ 取流统计 ================");
        System.out.println("  编码      = " + sink.codecName());
        System.out.printf("  实际时长  = %.1f 秒%n", sink.elapsedMs() / 1000.0);
        System.out.println("  重连次数  = " + sink.reconnects());
        System.out.println("  视频包数  = " + sink.videoPackets() + "（其中关键帧包 " + sink.idrPackets() + "）");
        System.out.printf("  视频字节  = %.2f MB%n", sink.videoBytes() / 1048576.0);
        if (!sink.audioPackets().isEmpty()) {
            sink.audioPackets().forEach((codec, count) ->
                    System.out.println("  音频包数  = " + count + "（codecID=" + codec + "，" + audioCodecName(codec) + "）"));
        } else {
            System.out.println("  音频包数  = 0");
        }
        System.out.println("  输出文件  = " + sink.filePath());
        if (sink.videoPackets() == 0) {
            System.out.println("  [警告] 未收到任何视频包，请检查设备是否被其他客户端占用或网络可达性。");
        } else {
            System.out.println("  可用 ffprobe \"" + sink.filePath() + "\" 或直接拖入 VLC 验证可播性。");
        }
    }

    private void doRecord(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：invr record <did> [秒数] [分段秒] [输出目录]");
            System.err.println("  秒数默认60（0=持续）；分段秒默认120；目录默认 recordings");
            System.exit(2);
        }
        String did = args[1];
        int seconds = args.length >= 3 ? Integer.parseInt(args[2]) : 60;
        long segSec = args.length >= 4 ? Long.parseLong(args[3]) : 120;
        java.io.File dir = new java.io.File(args.length >= 5 ? args[4] : "recordings");

        LoginSession session = requireSession();
        List<CameraDevice> cameras = listCamerasWithRecovery(session);
        CameraDevice device = cameras.stream().filter(c -> c.did().equals(did)).findFirst()
                .orElseThrow(() -> new MiCloudException("设备列表中未找到 did=" + did));
        System.out.println("准备录像 " + device.model() + " @ " + device.localIp() + " ...");

        AtomicReference<LoginSession> sessionRef = new AtomicReference<>(session);
        StreamHub hub = new StreamHub();
        RecordingSink sink = new RecordingSink(did, dir, segSec);
        CloudMissOpener opener = new CloudMissOpener(sessionStore, sessionRef, device, "", "hd", "1");

        CountDownLatch stopLatch = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            hub.unsubscribe(did, sink);
            sink.close();
            hub.shutdown();
            System.out.println("================ 录像统计 ================");
            System.out.printf("  视频包=%d 视频=%.2fMB 音频包=%d 重连=%d 分段=%d%n",
                    sink.videoPackets(), sink.videoBytes() / 1048576.0, sink.audioPackets(),
                    sink.reconnects(), sink.segmentCount());
            System.out.println("  输出目录 = " + dir.getAbsolutePath());
            stopLatch.countDown();
        };
        Thread hook = new Thread(cleanup, "invr-record-cleanup");
        Runtime.getRuntime().addShutdownHook(hook);

        hub.subscribe(did, device.model(), opener, sink);
        try {
            if (seconds > 0) {
                stopLatch.await(seconds, TimeUnit.SECONDS);
            } else {
                System.out.println("持续录像中（Ctrl+C 结束）...");
                stopLatch.await();
            }
        } finally {
            cleanup.run();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignore) {
                // JVM 已在关闭中
            }
        }
    }

    /**
     * 单通道扇出自检：同一 {@link StreamHub} 进程内对同一台摄像头同时挂"录像 sink + 裸流 sink"，
     * 运行期间周期性打印活跃上游设备数（恒为 1 即证明两个消费者复用同一条上游 CS2 连接）。
     * 可在外部用 Get-NetTCPConnection 同时核对到设备的 ESTABLISHED TCP 连接数=1。
     */
    private void doFanout(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：invr fanout <did> [秒数=25]");
            System.exit(2);
        }
        String did = args[1];
        int seconds = args.length >= 3 ? Integer.parseInt(args[2]) : 25;

        LoginSession session = requireSession();
        List<CameraDevice> cameras = listCamerasWithRecovery(session);
        CameraDevice device = cameras.stream().filter(c -> c.did().equals(did)).findFirst()
                .orElseThrow(() -> new MiCloudException("设备列表中未找到 did=" + did));
        System.out.println("单通道扇出自检 " + device.model() + " @ " + device.localIp()
                + "：同时挂 录像(MP4) + 裸流(Annex-B) 两个消费者，持续 " + seconds + " 秒");

        java.io.File dir = new java.io.File("recordings");
        AtomicReference<LoginSession> sessionRef = new AtomicReference<>(session);
        StreamHub hub = new StreamHub();
        RecordingSink rec = new RecordingSink(did, dir, 120);
        AnnexBFileSink raw = new AnnexBFileSink(did, "fanout-" + did + ".h265");
        CloudMissOpener opener = new CloudMissOpener(sessionStore, sessionRef, device, "", "hd", "1");

        CountDownLatch stopLatch = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            hub.unsubscribe(did, rec);
            hub.unsubscribe(did, raw);
            rec.close();
            try {
                raw.close();
            } catch (Exception ignore) {
                // 关闭异常忽略
            }
            hub.shutdown();
            System.out.println("================ 扇出自检统计 ================");
            System.out.printf("  录像Sink : 视频包=%d 音频包=%d 重连=%d 分段=%d%n",
                    rec.videoPackets(), rec.audioPackets(), rec.reconnects(), rec.segmentCount());
            System.out.printf("  裸流Sink : 视频包=%d 重连=%d%n", raw.videoPackets(), raw.reconnects());
            System.out.println("  -> 两消费者全程活跃上游设备数应恒为 1，且到设备 TCP 连接数=1");
            stopLatch.countDown();
        };
        Thread hook = new Thread(cleanup, "invr-fanout-cleanup");
        Runtime.getRuntime().addShutdownHook(hook);

        // 同一 hub、同一 did 订阅两次：第二次必须复用已存在的唯一上游，不得新建连接
        hub.subscribe(did, device.model(), opener, rec);
        hub.subscribe(did, device.model(), opener, raw);

        Thread sampler = new Thread(() -> {
            try {
                while (!stopLatch.await(5, TimeUnit.SECONDS)) {
                    System.out.printf("  [自检] 活跃上游设备数=%d（期望1） 录像视频包=%d 裸流视频包=%d%n",
                            hub.activeUpstreamCount(), rec.videoPackets(), raw.videoPackets());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "fanout-sampler");
        sampler.setDaemon(true);
        sampler.start();

        try {
            stopLatch.await(seconds, TimeUnit.SECONDS);
        } finally {
            cleanup.run();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignore) {
                // JVM 已在关闭中
            }
        }
    }

    /**
     * 常驻 RTSP 下行服务：{@code invr serve [端口=8554]}。
     * 播放器访问 {@code rtsp://<本机IP>:<端口>/<did>}；多播放器/录像并发对同一摄像头只占一条上游。
     */
    private void doServe(String[] args) throws Exception {
        int port = args.length >= 2 ? Integer.parseInt(args[1]) : 8554;
        LoginSession session = requireSession();
        List<CameraDevice> cameras = listCamerasWithRecovery(session);
        if (cameras.isEmpty()) {
            throw new MiCloudException("账号下未发现摄像头");
        }
        AtomicReference<LoginSession> sessionRef = new AtomicReference<>(session);
        StreamHub hub = new StreamHub();
        CameraConnector connector = new CameraConnector() {
            @Override
            public CameraDevice findDevice(String did) {
                return cameras.stream().filter(c -> c.did().equals(did)).findFirst().orElse(null);
            }

            @Override
            public MissOpener openerFor(CameraDevice device) {
                return new CloudMissOpener(sessionStore, sessionRef, device, "",
                        MissCommands.defaultQuality(device.model()), "1");
            }
        };
        RtspServer server = new RtspServer(port, hub, connector);
        server.start();

        String lanIp;
        try {
            lanIp = java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            lanIp = "127.0.0.1";
        }
        System.out.println("================ iNvr RTSP 下行服务 ================");
        System.out.println("监听端口：" + port + "（仅 RTP-over-TCP interleaved）");
        System.out.println("可拉流地址：");
        for (CameraDevice c : cameras) {
            String name = c.name() == null ? "" : "  (" + c.name() + ")";
            System.out.printf("  %-12s %s%n", c.did(),
                    "rtsp://" + lanIp + ":" + port + "/" + c.did() + name);
        }
        System.out.println("本机自测：rtsp://127.0.0.1:" + port + "/<did>");
        System.out.println("ffmpeg 示例：ffmpeg -rtsp_transport tcp -i rtsp://127.0.0.1:" + port
                + "/<did> -c copy out.mp4");
        System.out.println("按 Ctrl+C 退出（退出时自动断开全部上游）。");

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("正在关闭 RTSP 服务...");
            server.stop();
            hub.shutdown();
            stop.countDown();
        }, "invr-serve-shutdown"));
        stop.await();
    }

    private static String audioCodecName(int codecId) {
        return switch (codecId) {
            case 1024 -> "PCM";
            case 1026 -> "PCMU(G.711U)";
            case 1027 -> "PCMA(G.711A)";
            case 1032 -> "Opus";
            default -> "未知";
        };
    }

    // ------------------------------------------------------------------
    // whoami / logout
    // ------------------------------------------------------------------

    private void doWhoami() {
        Optional<LoginSession> session = sessionStore.load();
        if (session.isEmpty()) {
            System.out.println("未登录，请先执行 invr login <账号>");
            return;
        }
        LoginSession s = session.get();
        System.out.println("userId = " + s.userId());
        System.out.println("region = " + s.region());
        System.out.println("（passToken / serviceToken / ssecurity 已加密存储，不展示）");
    }

    private void doLogout() {
        sessionStore.clear();
        System.out.println("本地会话已清除。");
    }

    // ------------------------------------------------------------------
    // 公共辅助
    // ------------------------------------------------------------------

    private LoginSession requireSession() {
        return sessionStore.load().orElseThrow(() ->
                new MiCloudException("未登录，请先执行 invr login <账号>"));
    }

    /**
     * 列摄像头；code=401 时用 passToken 自动恢复会话并重试一次。
     */
    private List<CameraDevice> listCamerasWithRecovery(LoginSession session) {
        try {
            return new DeviceDiscovery(new MiCloudApi(session)).listCameras();
        } catch (MiCloudUnauthorizedException e) {
            System.out.println("会话已过期，正在用 passToken 恢复...");
            LoginSession renewed = new MiCloudClientImpl()
                    .loginWithToken(session.userId(), session.passToken(), session.region());
            sessionStore.save(renewed);
            return new DeviceDiscovery(new MiCloudApi(renewed)).listCameras();
        }
    }

    private static String mask(String hex) {
        if (hex == null || hex.length() <= 16) {
            return "****";
        }
        return hex.substring(0, 16) + "…(" + hex.length() + " chars)";
    }

    /**
     * CLI 交互实现：验证码图片落盘到数据目录 {@code .invr/captcha.jpg}（默认运行目录下），提示用户打开并输入。
     */
    static class CliInteraction implements LoginInteraction {

        @Override
        public String askCaptcha(byte[] captchaImage) {
            try {
                Path path = DataDir.get().resolve("captcha.jpg");
                Files.createDirectories(path.getParent());
                Files.write(path, captchaImage);
                System.out.println("需要图形验证码，图片已保存到：" + path);
                System.out.println("请打开图片查看，然后输入验证码：");
                return SCANNER.nextLine();
            } catch (IOException e) {
                throw new MiCloudException("验证码图片保存失败：" + e.getMessage(), e);
            }
        }

        @Override
        public String askVerifyCode(String kind, String maskedTarget) {
            String channel = "Phone".equals(kind) ? "手机" : "邮箱";
            String target = (maskedTarget == null || maskedTarget.isBlank())
                    ? "" : "：" + maskedTarget;
            System.out.println("需要二次验证，验证码已发送至" + channel + target);
            System.out.print("请输入收到的" + channel + "验证码：");
            return SCANNER.nextLine();
        }
    }
}
