# 飞牛 fnOS FPK 打包工具（Native / 自带 JRE）

把 Spring Boot jar 包一键打包成飞牛 fnOS 应用安装包（.fpk），不需要 Docker。

## 使用步骤（3步）

### 第1步：准备文件

把这两样东西放到 `input/` 目录里：

```
input/
├── app.jar              ← 你的 Spring Boot 可执行 jar
└── jre/                 ← Linux x64 JRE 解压目录
    ├── bin/
    │   └── java          ← 确保这个文件存在
    └── ...
```

**JRE 下载**：去 [Eclipse Temurin](https://adoptium.net/temurin/releases/) 下载 **Linux x64** + **JRE** + `.tar.gz` 格式。注意是 Linux 版，不是 Windows 版！

### 第2步：修改配置

用记事本打开两个文件，改成你自己的应用信息：

**① `template\manifest`** — 改这些行：
```
appname=com.example.myapp        ← 改成你的应用唯一标识（反向域名格式）
version=1.0.0                    ← 版本号
display_name=我的Spring应用        ← 应用显示名称
service_port=8080                ← 你的 Spring Boot 端口
```

**② `template\app\ui\config`** — 改这两处：
```json
"com.example.myapp.main": {       ← 前面的 com.example.myapp 要和 manifest 里的 appname 一致
  "title": "我的Spring应用",
  "port": "8080",                 ← 端口要和 manifest 里的 service_port 一致
```

> 如果要改默认 JVM 内存大小，打开 `template\cmd\install_callback`，修改里面的 512 和 128 这两个数字。

### 第3步：双击运行

双击 **`build.bat`**，等它跑完。

打包成功后，生成的 `.fpk` 文件在 `output\` 目录里，传到飞牛 NAS 安装即可。

---

## 目录说明

```
飞牛java打包/
├── build.bat              ← 双击运行的打包脚本
├── input/                 ← 放你的 jar 和 JRE
│   ├── app.jar
│   ├── jre/
│   └── icons/             ← 图标（可选）
│       ├── ICON.PNG       ←   64x64
│       └── ICON_256.PNG   ←   256x256
├── template/              ← 应用模板（改配置的地方）
│   ├── manifest           ← ★ 应用基本信息
│   ├── cmd/               ← 生命周期脚本（一般不用改）
│   ├── config/            ← 权限和资源声明（一般不用改）
│   ├── wizard/            ← 安装向导配置（一般不用改）
│   └── app/
│       └── ui/
│           └── config     ← ★ 桌面图标和端口
├── build/                 ← 构建临时目录（自动生成，不用管）
├── output/                ← 生成的 .fpk 在这里
└── README.md
```

★ 标记的是你需要改的文件，其他一般不用动。

---

## 常见问题

### Q: 双击 build.bat 一闪而过？
打开 PowerShell 或 CMD，cd 到这个目录，手动运行 `build.bat`，这样能看到错误信息。

### Q: 提示 "fnpack 不是内部或外部命令"？
说明 fnpack 没装或者没加到系统 PATH。去 [飞牛开发者文档](https://developer.fnnas.com/docs/cli/fnpack) 下载 Windows 版 fnpack，放到某个目录然后加到系统环境变量 PATH 里。

### Q: JRE 该选哪个版本？
- Spring Boot 3.x → JDK 17 或 21 的 JRE
- Spring Boot 2.x → JDK 8 或 11 的 JRE

下载时选 **Linux x64** + **JRE** + `.tar.gz`。

### Q: 应用日志和数据在哪？
- 日志：`/vol[x]/@appdata/[appname]/logs/app.log`
- 数据：`/vol[x]/@appdata/[appname]/`
- 配置：`/vol[x]/@appconf/[appname]/`

卸载时这些目录会保留，不会丢数据。

### Q: 怎么减小 FPK 体积？
标准 JRE 约 180MB。可以用 `jlink` 精简：
```bash
# 在有 JDK 的 Linux 机器上
jlink --add-modules java.base,java.logging,java.sql,java.naming,java.management \
      --output jre --strip-debug --no-header-files --compress=2
```
把生成的 `jre` 目录替换 `input/jre/` 即可。
