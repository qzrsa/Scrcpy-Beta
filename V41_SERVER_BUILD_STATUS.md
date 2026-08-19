# Scrcpy 服务端 v4.1 对齐 & 编译状态

> 最后更新：2026-08-19（编译已在本机验证通过）

## 一、结论先行

**「编译服务端模块」≠「升级到 4.1」。**
编译只证明已经完成的外科式改动（编码器优选 + 版本标记）能在 **AGP 8.7.2 / Gradle 8.9 / compileSdk 36** 下干净通过 Java 编译；它**不等于**获得 Genymobile/scrcpy v4.1 的完整能力（Android 15/16 适配、输入/多指健壮性、剪贴板稳定性等仍需后续工作）。

**本次编译验证结果：✅ 通过。**
- 命令：`./gradlew :server:assembleRelease --no-daemon`
- 关键任务 `:server:compileReleaseJavaWithJavac` 成功（验证 v4.1 改动可编译）。
- 产物：`server/build/outputs/apk/release/server-release-unsigned.apk`（约 18KB）。
- `BUILD SUCCESSFUL in 1m 9s`。

## 二、已经完成的代码改动

仓库路径：`D:\WorkBuddy\Scrcpy Updete\repo\Scrcpy`（git 根在此，项目代码在 `Scrcpy/` 子目录）。

1. **v4.1 对齐改造（核心，已编译验证）**
   - 编码器优选：`Encodetools.selectEncoderName`（自定义协议服务端编码器选择逻辑）。
   - 版本标记：`server/build.gradle` 中 `versionCode 40100` / `versionName '4.1.0'`（非官方发布版号，仅作谱系标记，向后兼容 `videoEncoder`/`audioEncoder` 选项）。
2. **构建配置升级（此前编译阻塞的预排除项，已修复并验证）**
   - `app/build.gradle`、`server/build.gradle`：AGP `8.2.2` → `8.7.2`（兼容 compileSdk 36，AGP 8.2.2 最高只支持 API 34）。
   - `gradle/wrapper/gradle-wrapper.properties`：`distributionUrl` 改为 `gradle-8.9-bin.zip`（去掉了旧 sha256 校验和）。
   - `settings.gradle`：`include ':app'` 与 `include ':server'` 均保留（最终态，非临时隔离）。
   - JDK：系统 JDK 21 满足 AGP 8.7/Gradle 8.9 对 JDK 17+ 的要求，无需另装 JDK 17。

## 三、本机 Android SDK 安装（已完成）

- 下载渠道：`dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip`（沙箱对 Google 域名新建连接间歇性失败，需重试；下载完成后 zip 曾被残留进程独占锁定，锁释放后成功解压）。
- 安装位置：`D:\WorkBuddy\android-sdk`（避免仓库路径含空格导致 aapt2/SDK 路径问题）。
- 已装组件：`platform-tools`、`platforms;android-36`、`build-tools;34.0.0`、`build-tools;35.0.0`（AGP 8.7.2 默认用 35.0.0）。
- `local.properties` 已写入 `sdk.dir=D:\WorkBuddy\android-sdk`（该文件被 `.gitignore` 忽略，不会进仓库）。

## 四、可复用的编译流程（已在本机实测通过）

```bash
# 0) 环境
export ANDROID_HOME='D:/WorkBuddy/android-sdk'
export JAVA_HOME='C:/Program Files/Microsoft/jdk-21.0.8.9-hotspot'

# 1) 装命令行工具链（下载若失败重试；解压后整理成 latest 目录）
#    unzip ct.zip -d $SDK/cmdline-tools && mv $SDK/cmdline-tools/cmdline-tools $SDK/cmdline-tools/latest
# 2) 接受 license + 装组件（PowerShell 原生跑 .bat 最稳，用 (1..N | ForEach {"y"}) 喂 y）
# 3) 写 local.properties: sdk.dir=D:\WorkBuddy\android-sdk
# 4) 编译 server 模块
cd D:\WorkBuddy\Scrcpy Updete\repo\Scrcpy
./gradlew :server:assembleRelease --no-daemon --console=plain
# 产物：server/build/outputs/apk/release/server-release-unsigned.apk
```

## 五、推送约定与现状（已执行）

- **不**推送到 `qzrsa/Scrcpy`（主仓库）—— 全程未推送。
- 推送目标 `qzrsa/Scrcpy-Beta`：**已创建并推送成功**，仓库结构与主仓库一致（代码直接置于根，无外层 `Scrcpy/`）。
  - 当前 `main` = `96eccaaf`（根结构版，含 v4.1 对齐 + 编译配置升级）。
  - 父链：`96eccaaf` → `06ae3ecf` → `3f169588`。
- 推送通道：本沙箱对 GitHub 的 **HTTPS git pack 上传被中间传输层确定性破坏**（每次 `remote unpack failed: index-pack failed`），且 token 缺 `admin:public_key`/`delete_repo`，无法用 SSH 或删脏仓库。改用 **GitHub Git Data API（REST JSON）** 绕过 pack 协议完成推送（脚本 `D:\WorkBuddy\Scrcpy Updete\repush_beta_root.py`）。

## 六、完整 v4.1 对齐进度清单（逐项推进）

> 对照官方 scrcpy 4.1 变更（VP8/VP9、尺寸约束修复、剪贴板/稳定性等）+ 本仓库自实现缺口，拆成 5 项。逐项改 → 编译验证 → 提交 → 推 `qzrsa/Scrcpy-Beta`。

| # | 项目 | 状态 | 说明 |
|---|---|---|---|
| 1 | 多指触控 Pointer index 修正 | ✅ 已完成 | 修复 `touchEvent` 误用 `pointer.id` 而非数组下标构造 `ACTION_POINTER_UP/DOWN`，多指手势不再错乱；对齐上游 scrcpy `Controller` 实现 |
| 2 | 剪贴板跨版本稳定性加固 | ✅ 已完成 | 新增客户端主动拉取剪贴板命令（case 12）+ `getClipboardText/markClipboardSynced`，双向/初始同步更稳；`getText` 异常安全 |
| 3 | 分辨率/编码器尺寸约束对齐 | ✅ 已完成 | `VideoEncode` 新增 `resolveCodecInfo/clampVideoSizeToEncoder/applyEncoderFallbackSize`：按编码器 `VideoCapabilities`（alignment + 支持宽高上界）对齐 videoSize，`configure` 失败再兜底重试；用 `MediaCodecList` 解析兼容 Android 9（不用 API29+ 的 `getCodecInfo()`）；发包顺序调整为对齐后发送 |
| 4 | Android 15/16 (API 35/36) 适配加固 | ✅ 已完成 | 补全 lint 守卫：`SurfaceControl` 类级补 `BlockedPrivateApi`、`InputManager`/`DisplayManager` 补 `PrivateApi`、`FakeContext.getDeviceId()` 加 `@Override`（compileSdk 36 下 `Context.getDeviceId()` API31+ 已存在）；`ClipboardManager` 签名探测已覆盖 API34 全组合、API35/36 大概率沿用；`DisplayManager` 反射字段名稳定。**运行期反射路径（SurfaceControl 静态方法）需真机/模拟器在 API35/36 验证** |
| 5 | VP8/VP9 编码兜底（4.1 头号特性） | ✅ 已完成 | 协商字节扩展为枚举（0=H264/1=H265/2=VP8/3=VP9）；服务端优先 H265→H264，二者不可用兜底 VP9/VP8（`EncodecTools.isSupportVP8/9/AVC`）；**补全 csd 发送**（首帧前发 csd-0，AVC 另发 csd-1），修复此前服务端不发 csd 导致客户端读取错位的协议缺口，VP8/VP9 无 csd 自动跳过；客户端 `ClientPlayer` 按枚举选解码 mime、`VideoDecode` 按类型设 csd、`DecodecTools.getVideoDecoder(int)` 支持四codec。**注意**：此改动同时修正了 H264/H265 既有 csd 协议，且 app 需内置 server 二进制（`app/src/main/res/raw/scrcpy_server`） |

## 七、真机运行验证（已完成）

> 编译通过 ≠ 运行正确。5 项 v4.1 改动均编译通过后，已在**真实设备**上做运行验证，抓出并修复一个真机才暴露的运行时 NPE。

**验证环境**
- 设备：`SCG23`（Android 14 / API 34 / arm64-v8a），通过 `adb` over TCP/IP 连接。
- 方法：把 server 以 `app_process -Djava.class.path=... qzrs.Scrcpy.server.Server serverPort=8886 ...` 拉起（与 app 实际启动方式一致），`adb forward` 打通隧道，用 Python 测试客户端连接 main+video 双 socket 触发 `VideoEncode.init()`，并 `logcat` 抓崩溃；app 侧安装 `app-debug.apk` 拉起 `MainActivity` 看是否崩溃。

**首轮失败 —— 抓到真机 NPE（第 5 项引入的回归）**
```
java.lang.NullPointerException: ArrayList.add on a null object reference
	at EncodecTools.getEncodecList(EncodecTools.java:30)
	at EncodecTools.isSupportH265(EncodecTools.java:61)
	at VideoEncode.init(VideoEncode.java:47)
```
- 根因：第 5 项新增 `avcEncodecList` / `vp8EncodecList` / `vp9EncodecList` 字段，但 `getEncodecList()` 只初始化了原来的 `hevcEncodecList` / `opusEncodecList`，三个新列表为 `null`，第 30 行 `avcEncodecList.add()` 直接 NPE。编译器不检查空指针，故编译通过、运行才炸。
- 修复：在 `getEncodecList()` 顶部把 `avcEncodecList` / `vp8EncodecList` / `vp9EncodecList` 一并 `new ArrayList<>()`（commit 见下）。

**修复后重跑 —— 全部通过 ✅**
| 验证项 | 结果 |
|---|---|
| 编码器选择（协商字节） | `codecTypeId=1` → **H265(HEVC)**，H265 优先链路生效 |
| 分辨率尺寸约束（v4.1 size-constraints） | `videoSize=608x1088`（maxSize=1080 + 16 对齐），约束对齐生效 |
| 编码器实际产出 | 收到 **13 条视频消息**，首帧 payload 80 字节 = HEVC 的 csd-0(SPS/PPS)，`VideoEncode.init` 全链路跑通 |
| 主 socket size-event | `03 00000260 00000440` = 608×1088，正确 |
| app 启动 | `am start` 拉起 `MainActivity` 进程存活，**无 AndroidRuntime 崩溃 / 无 "has stopped"** |

> 注：server 日志里的 `EOFException` 是测试客户端断开后的正常退出（非 bug）。Android 15/16（API35/36）的 SurfaceControl 反射路径仍**仅在 API35/36 真机/模拟器验证**，本机为 API34。

## 八、下一步

1. **API35/36 真机/模拟器专项验证**：当前设备是 API34，SurfaceControl 反射路径在 API35/36 仅做了 lint 守卫，建议找一台 Android 15/16 设备确认 `SurfaceControl` 反射仍可用（第 4 项标注的待验证点）。
2. **端到端联机验证（可选）**：双机或单机回环跑一次"控制端 app ↔ 被控端 server"的完整投屏/触控/剪贴板，确认 VP8/VP9 兜底、多指、双向剪贴板在真实交互下无误。
3. 后续改动继续提交并推送到 `qzrsa/Scrcpy-Beta`（复用 Git Data API 脚本）。
