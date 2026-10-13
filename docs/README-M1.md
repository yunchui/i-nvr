# iNvr

自研 NVR 服务：小米摄像头 **miss/mess + CS2** 私有协议接入，下行 RTSP 转发与录像；
对同一摄像头，录像与 RTSP 转发共用 **1 条上游连接**（StreamHub 单通道扇出）。

- 上行：仅支持 2020 年后小米统一协议（miss/mess + CS2），**不做 legacy/TUTK，不做 RTSP/ONVIF 接入**
- 语言：Java 17+（Netty / BouncyCastle）
- 参考实现：go2rtc `pkg/xiaomi`（MIT，作为协议翻译蓝本）；MiBeeNVR 为 AGPL-3.0，仅参考架构
- 详细设计：[`docs/design/01-xiaomi-protocol-design.md`](docs/design/01-xiaomi-protocol-design.md)

## 当前进度：M1（云端认证与设备发现）已完成

- 米家账号密码登录：MD5 哈希、`serviceLogin` / `serviceLoginAuth2`、重定向链 `finishAuth`
- 图形验证码分支（图片落盘后人工输入）
- 短信 / 邮箱二次验证（2FA，flag 4=手机 / 8=邮箱）
- `passToken` 持久化与重启后 `LoginWithToken` 免密恢复（业务 API 401 时自动恢复重试）
- 业务 API 签名：nonce / SHA256 signedNonce / RC4 表单加密 / SHA1 双签名 / 响应解密
- 设备列表拉取与摄像头过滤（`.camera.` / `.cateye.` / `.feeder.`）
- `miss_get_vendor` 协商：本地 X25519 公钥上报，换取设备公钥、sign、CS2 参数（vendor 非 4 直接判定不支持）
- 会话凭据 AES-GCM 加密存储（`~/.invr/`）

## 环境要求

- JDK 17 或以上（已在 JDK 21 验证）
- Maven（仓库内置 `maven-settings.xml`，走阿里云镜像，不污染全局配置）

## 构建

```powershell
# 在项目根目录
mvn -s maven-settings.xml package
# 产物：target/invr.jar（fat jar）
```

## 运行

```powershell
# 登录（不把密码写在命令行则安全输入，不回显；验证码图片保存到 ~/.invr/captcha.jpg）
java -jar target/invr.jar login <小米账号>

# 列出账号下摄像头（DID / 型号 / 局域网 IP / 名称）
java -jar target/invr.jar devices

# miss 协商 CS2 连接参数（每次取流前的云端步骤；私钥仅存内存不打印）
java -jar target/invr.jar negotiate <did>

# 查看当前会话 / 退出登录
java -jar target/invr.jar whoami
java -jar target/invr.jar logout
```

海外区域设置环境变量 `INVR_REGION`（de/i2/ru/sg/us），默认中国区 `cn`。

会话文件：

- `~/.invr/session.json`：AES-GCM 加密后的会话凭据
- `~/.invr/master.key`：32 字节本机主密钥
- `~/.invr/captcha.jpg`：登录图形验证码（登录后可删除）

## 工程结构

```
src/main/java/com/invr/
├── InvrApplication.java        # CLI 入口（login/devices/negotiate/whoami/logout）
├── cloud/                      # 米家云
│   ├── MiCloudClientImpl.java  # 登录全流程（验证码 / 2FA / finishAuth / token 恢复）
│   ├── CloudApiSigner.java     # nonce / RC4 / SHA1 业务 API 签名与响应解密
│   ├── MiCloudApi.java         # 签名 POST 通用调用、code 校验（401 抛恢复异常）
│   ├── DeviceDiscovery.java    # device_list_page 设备发现 / 门铃唤醒
│   ├── VendorNegotiator.java   # miss_get_vendor 协商（X25519 + vendor=4 校验）
│   ├── LoginSession / LoginInteraction / MemoryCookieJar / ...
├── crypto/                     # Rc4Cipher（含1024丢弃）/ X25519Keys / ChaCha20Cipher
├── model/                      # CameraDevice / P2pConnectParams
├── store/                      # SessionStore（AES-GCM 加密落盘）
└── （M2 待建）p2p/cs2、miss、hub、recorder、rtsp
```

## 测试

```powershell
mvn -s maven-settings.xml test
```

- RC4：RFC 6229 已知向量 + 1024 字节丢弃行为
- ChaCha20：RFC 8439 §2.3.2 全零向量（验证 12 字节 nonce 布局）+ 加解密往返
- X25519：双向 ECDH 一致
- 签名器：表单结构、固定 nonce 确定性、响应解密往返
- 会话存储：加密往返、密文不含敏感明文

## 后续里程碑

- **M2（最高风险，下一步）**：CS2 UDP 打洞握手（32108 / 0x41-0x43）、DRW 帧与 ACK 重传、
  DataChannel 重组、MISS AuthReq/0x1001 加密命令、VideoStart、32 字节媒体头解密、
  Annex-B→AVCC，输出可播放裸流
- **M3**：StreamHub（GOP 缓存 + 订阅者扇出 + 背压）、MP4 分段录像
- **M4**：自研 RTSP 输出（`rtsp://NVR:8554/<did>`，不转码），抓包验收录像与 RTSP 同开时仅 1 条 CS2 连接
- **M5**：Web 管理端、指标与运维产品化

## 合规提示

仅用于接入**本人自有**小米摄像头并在局域网内使用；凭据加密存储、不做多账号并发登录；
本项目为非官方逆向协议实现，小米云端/固件升级可能导致协议失效，需自行评估使用风险。
