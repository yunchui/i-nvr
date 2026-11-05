# iNvr 监控大屏客户端（wall-client）

共享看板的原生 RTSP 配套客户端，用于无人值守电视墙。浏览器看板走 HLS（分片 3s + 窗口，
端到端延迟约 2–4s），本客户端通过 **RTSP-over-TCP 直连 + FFmpeg 低延迟解码 + JavaFX
PixelBuffer 直送上屏**，目标把延迟压到 0.5–1.5s，并复用服务端共享看板已有的布局、槽位、
在线状态与事件推送，不新增任何服务端配置入口。

- 技术栈：Java 17 / JavaFX 21 / JavaCV(FFmpeg) 1.5.10 / OkHttp 4 / Jackson 2 / slf4j-simple
- 支持布局：grid4（2×2）、grid6（1 大 5 小）、grid9（3×3）
- 事件联动：grid4/9 全屏覆盖、grid6 主位交换，规则与 Web 端 `share.js` 同构
- 独立 Maven 工程，不并入主服务 `invr.jar`，可单独打包部署到 Windows 大屏机

> 完整设计与分期（M1–M4）见主工程 `docs/design/03-rtsp-wall-client-design.md`。

## 目录结构

```
wall-client/
├─ pom.xml                     # shade fat jar + javafx-maven-plugin
├─ maven-settings.xml          # 与主工程一致的阿里云镜像配置
└─ src/main/
   ├─ java/com/invr/wall/
   │  ├─ Launcher.java         # fat jar Main-Class（单实例锁 + 启动 JavaFX）
   │  ├─ support/              # Json、Clock（时钟校准）、SingleInstance
   │  ├─ config/               # WallConfig（共享链接解析）、原子落盘 Store
   │  ├─ board/                # board DTO、BoardClient 轮询、ShareLayout
   │  ├─ event/                # SseClient（自解析 text/event-stream）
   │  ├─ media/                # RtspPlayer、PlayerPool、PlayerState
   │  └─ ui/                   # WallApplication、WallController、CellPane、设置页
   └─ resources/
      ├─ wall.css
      └─ simplelogger.properties
```

## 环境要求

| 项 | 要求 |
|---|---|
| 操作系统 | Windows 10/11 x86_64（依赖已固定 windows classifier） |
| 运行时 | JDK / JRE 17 及以上（开发机使用 IntelliJ 内置 JBR 21 编译，release 17） |
| 网络 | 大屏机能同时访问服务端 HTTP 端口（默认 9080）与 RTSP 端口（默认 9554） |
| 服务端 | 已部署含 board `rtsp{host,port}` 字段的 iNvr 版本；旧版本无该字段时回退 9554 与共享链接主机 |

## 开发运行

工程自带与主工程相同的 `maven-settings.xml`，无全局 Maven 时可直接指定 IDEA 内置 Maven：

```powershell
$env:JAVA_HOME = "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.4\jbr"
cd C:\Users\IndSvr\Desktop\iNvr\wall-client
& "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.4\plugins\maven\lib\maven3\bin\mvn.cmd" `
    -s maven-settings.xml javafx:run
```

普通环境下等价于：

```bash
mvn -s maven-settings.xml javafx:run
```

首次启动没有配置时进入设置页，粘贴共享看板链接（支持直连与 fnOS 网关前缀
`/app/{appname}/share/...` 两种形态），保存前会实际请求一次 board 校验，410/网络不通会直接报错。

## 打包

### 1. fat jar（约 39MB，含全部 Java 依赖与 FFmpeg Windows 原生库）

```bash
mvn -s maven-settings.xml clean package
```

产物：`target/invr-wall.jar`，Main-Class 为 `com.invr.wall.Launcher`，运行：

```bash
java -jar target/invr-wall.jar          # 带控制台，便于首装排查
javaw -jar target/invr-wall.jar         # 无控制台，适合常驻
```

### 2. jpackage 原生 app-image（推荐大屏机部署）

> 注意：IntelliJ 内置 JBR 不含 `jpackage`，请使用完整 JDK 17+（如 Temurin）执行。

```powershell
mvn -s maven-settings.xml clean package
mkdir build\lib -Force | Out-Null
Copy-Item target\invr-wall.jar build\lib\

jpackage --type app-image --name iNvrWall `
  --input build\lib --main-jar invr-wall.jar `
  --main-class com.invr.wall.Launcher --win-console --vendor iNvr
```

输出 `build/iNvrWall/iNvrWall.exe`，目录整体拷贝即可运行，目标机无需另装 Java。
需要 MSI 安装包时把 `--type app-image` 改为 `--type msi`。

## 部署为开机自启（无人值守）

方式一：启动目录（最简单）

1. `Win+R` 输入 `shell:startup` 打开当前用户启动目录；
2. 新建快捷方式，目标填 `javaw.exe -jar "C:\path\invr-wall.jar"`（app-image 则直接指向 `iNvrWall.exe`）。

方式二：任务计划程序（可设失败重启、后台运行）

```powershell
schtasks /Create /TN "iNvrWall" /SC ONLOGON /RL HIGHEST `
  /TR '"C:\Program Files\Java\jdk-17\bin\javaw.exe" -jar "C:\path\invr-wall.jar"'
```

进程内置数据目录文件锁（`wall.lock`），重复启动/重复自启只会保留一个实例。

## 使用与运维

| 操作 | 方式 |
|---|---|
| 重新配置看板 | 运行中按 `Ctrl+Shift+S` 打开设置页，改链接后重新校验保存 |
| 全屏切换 | `F11`；启动默认全屏 |
| 手动刷新连接 | `F5`：立即断开并重连全部在播画面（含事件全屏路），重置失败计数，顶部提示 3 秒 |
| 失效提示 | board/SSE 返回 410 时进入"共享看板不可用"遮罩，不会黑屏静默运行 |
| 日志 | 控制台或重定向输出，INFO 级含建连、状态机、重连、事件切换记录 |

数据与配置目录优先级：

1. JVM 参数 `-Dinvr.wall.data.dir=D:\wall`
2. 环境变量 `INVR_WALL_DATA_DIR`
3. 默认 `用户工作目录/.invr-wall/`

目录内含：`wall-config.json`（原子 tmp+move 写入）、`wall.lock`（单实例锁）。

## 关键行为说明

- **只拉视频 track**：仅 SETUP/解码视频（H.265/H.264），一期静音，忽略音频 track。
- **低延迟参数**：`rtsp_transport=tcp`、`low_delay`、`probesize=32768`、`analyzeduration=500000`、
  套接字读超时 `timeout=3s`（禁用 `nobuffer`，它会令 H.265 重组丢参考帧而灰屏）；
  **按摄像头原始分辨率拉流、native 不缩放**，swscale 仅转 BGRA 直写 PixelBuffer，到格子的缩放
  交由 ImageView(GPU)，上屏限频约 15fps。
- **布局与窗口**：格子只按网格百分比分割屏幕，与视频分辨率无关；全屏/窗口化均等比自适应，
  窗口最小 480×270，F11 切换全屏。
- **看门狗**：帧龄 10s 无新帧或建连 20s 无首帧即重建取流；连续失败 5 次进 ERROR，
  等下一次 board reconcile 显式重启，避免对坏路无限重连。
- **断流恢复**：摄像头分钟级断流由服务端自动重连，客户端表现为短暂"重连中"占位，不销毁槽位。
- **事件兜底**：除 SSE `end` 外，本地按 `expireAt` 挂计时器，SSE 丢消息也能按时复原。
- **时钟校准**：每次 board 响应以 `serverTime` 校准本地偏移，事件到期判断基于服务端时钟。

## 已知限制与后续（M4 真机实测项）

以下为设计内已知项，需在实际大屏机与真机摄像头上验证后再优化：

1. 9 路 3.5K H.265 当前为**软解**，CPU 占用与可承载路数待实测；不达标时引入
   D3D11VA/NVDEC 硬解或切换 libVLC 后端（设计文档已列备选路径）。
2. 端到端延迟目标 0.5–1.5s 待真机对照测量（当前环境无摄像头/显示器，未做运行期实测）。
3. grid6 事件交换后小格路进入主位会略糊（一个事件周期内），后续按槽位动态重建播放器。
4. 音频 track1（OPUS）未启用；安卓大屏（Media3/ExoPlayer）分支未启动。
5. 一屏固定一个看板，无多看板轮巡。
