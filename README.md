# Scrcpy（安卓控安卓 / 无线投屏远控）

基于开源 [Scrcpy](https://github.com/Genymobile/scrcpy) 思路实现的 Android 投屏 / 远控工具：在**一台安卓设备**上运行本 App，即可控制**另一台安卓设备**（通过 USB 或局域网 TCP）。服务端以 `app_process` 方式加载，无需 root。**服务端内部实现已对齐至上游 Genymobile/scrcpy v4.1**（编码器选择等能力）。

## 功能特性

- 实时屏幕镜像（视频编码 H264/H265 可选）
- 双向触控（含多指）、按键注入
- 音频转发（Opus/AAC 可选）
- 剪贴板双向同步
- 按设备独立配置：最大分辨率、最大码率、帧率、是否保持唤醒、H265/Opus 开关、开机自启指定 App
- 悬浮窗迷你控制器 + 全屏观看页
- USB（ADB）与无线（TCP）两种连接方式

## 构建

仓库为 Gradle 多模块工程（`app` 主程序 + `server` 被 app_process 加载的服务端 APK）。

```bash
# 1. 先构建并拷贝服务端 APK 到 app 的 raw 资源（:app:assembleRelease 依赖此步骤）
cd Scrcpy
./gradlew :server:copyRelease

# 2. 再构建主程序 Release 包
./gradlew :app:assembleRelease
```

输出：`Scrcpy/app/build/outputs/apk/release/`。

> 也可用根目录 GitHub Actions（`android_build.yml`，手动触发 `workflow_dispatch`）完成签名打包与发版；版本号默认自动递增并回写仓库。

要求：Android SDK（compileSdk 36）、JDK 17、对应平台 NDK（仅 `arm64-v8a` / `armeabi-v7a`）。

## 使用

1. 在两台设备上安装本 App。
2. 受控设备：授予**悬浮窗权限**（必需）与**USB 调试**权限。
3. 控制设备：通过 USB 线或同一局域网连接受控设备，按提示授权。
4. 在设备详情页按需调节分辨率 / 码率 / 帧率等参数后开始投屏。

### 权限说明

| 权限 | 用途 |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗迷你控制器（必需） |
| `INTERNET` / `ACCESS_WIFI_STATE` | 无线连接与网络状态 |
| `USB_PERMISSION` + `usb.host` | USB 连接 |

## 架构

```
app (主程序, 普通 Android App)
 ├─ UI / 悬浮窗 / 设备列表 / 设置
 ├─ adb / USB-TCP 通道
 ├─ client (解码视频/音频, 发送控制指令)
 └─ res/raw/scrcpy_server.jar  ← server 模块产物(由 app_process 在受控端加载)

server (被 app_process 加载的服务端)
 ├─ MediaCodec 视频/音频编码
 ├─ 屏幕采集(VirtualDisplay / SurfaceControl)
 └─ 通过两条 TCP(socket) 与 client 通信：main(控制/音频/剪贴板) + video(视频帧)
```

通信要点：

- **主通道（main socket）**：控制指令、音频帧、剪贴板、心跳包复用同一流，写入已做同步串行化避免交错。
- **视频通道（video socket）**：独立承载视频帧，主/视频两条流均关闭 TCP Nagle（`TCP_NODELAY`）以降低延迟。
- 视频/音频发送使用**复用缓冲**，避免每帧 `ByteBuffer.allocate` 引起的 GC 抖动。
- 服务端编码线程使用**有限超时**（`dequeueOutputBuffer(..., 1000)`）而非无限等待，便于连接关闭时优雅退出。

## 已知限制 / 计划

- 后台连接依赖系统调度，长时间后台可配合前台服务保活（后续优化）。
- 码率由设备端**手动选择上限**（见设备详情页「最大码率」），不做激进的自动自适应，以保证画质稳定。
- Release 包的 ProGuard 已收紧为仅保留 manifest 组件并保留行号，建议发版后做回归验证。
