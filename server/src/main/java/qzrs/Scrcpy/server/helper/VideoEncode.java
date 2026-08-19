/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package qzrs.Scrcpy.server.helper;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.system.ErrnoException;
import android.util.Pair;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;

import qzrs.Scrcpy.server.Server;
import qzrs.Scrcpy.server.entity.Device;
import qzrs.Scrcpy.server.entity.Options;
import qzrs.Scrcpy.server.wrappers.SurfaceControl;

public final class VideoEncode {
  private static MediaCodec encedec;
  private static MediaFormat encodecFormat;
  public static boolean isHasChangeConfig = false;
  // 协商编码类型：0=H264(AVC) 1=H265(HEVC) 2=VP8 3=VP9（对齐 scrcpy v4.1 的 VP8/VP9 兜底）
  private static int codecTypeId;
  private static String codecMime;
  // csd 是否已在首帧前发出（仅首帧前发一次，避免运行时格式变化污染视频流）
  private static boolean csdSent = false;

  private static IBinder display;

  // 自适应码率下限(bps)，避免画质崩塌；当前生效码率，用于去重避免重复 setParameters
  private static final int AUTO_BITRATE_FLOOR = 1_000_000;
  private static int currentBitrate = 0;
  // 已编码输出帧数（用于向客户端上报采集帧率）
  public static volatile long encodedFrameCount = 0;

  public static void init() throws InvocationTargetException, NoSuchMethodException, IllegalAccessException, IOException, ErrnoException {
    // 对齐 scrcpy v4.1：优先 H265（若支持），否则 H264；H264/H265 均不可用时兜底 VP9/VP8（几乎总是可用）
    if (Options.supportH265 && EncodecTools.isSupportH265()) {
      codecMime = MediaFormat.MIMETYPE_VIDEO_HEVC;
      codecTypeId = 1;
    } else if (EncodecTools.isSupportAVC()) {
      codecMime = MediaFormat.MIMETYPE_VIDEO_AVC;
      codecTypeId = 0;
    } else if (EncodecTools.isSupportVP9()) {
      codecMime = MediaFormat.MIMETYPE_VIDEO_VP9;
      codecTypeId = 3;
    } else if (EncodecTools.isSupportVP8()) {
      codecMime = MediaFormat.MIMETYPE_VIDEO_VP8;
      codecTypeId = 2;
    } else {
      // 终极兜底：即便设备异常也尝试 AVC
      codecMime = MediaFormat.MIMETYPE_VIDEO_AVC;
      codecTypeId = 0;
    }
    // 创建Codec（内部按所选编码器的 VideoCapabilities 约束对齐 videoSize，v4.1 size-constraints 修复）
    createEncodecFormat();
    // 发包：协商编码类型枚举 + 已对齐的视频尺寸（与客户端一致）
    ByteBuffer byteBuffer = ByteBuffer.allocate(9);
    byteBuffer.put((byte) codecTypeId);
    byteBuffer.putInt(Device.videoSize.first);
    byteBuffer.putInt(Device.videoSize.second);
    byteBuffer.flip();
    Server.writeVideo(byteBuffer);
    // 创建显示器
    display = SurfaceControl.createDisplay("scrcpy", Build.VERSION.SDK_INT < Build.VERSION_CODES.R || (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S".equals(Build.VERSION.CODENAME)));
    // 启动编码
    startEncode();
  }

  private static void createEncodecFormat() throws IOException {
    // 对齐 scrcpy v4.1：优先选用硬件编码器，避免部分机型默认编码器出绿屏/紫屏/卡顿；支持手动指定
    String encoderName = EncodecTools.selectEncoderName(codecMime, Options.videoEncoder);
    MediaCodecInfo codecInfo;
    try {
      codecInfo = encoderName != null ? resolveCodecInfo(encoderName, codecMime) : null;
      encedec = encoderName != null ? MediaCodec.createByCodecName(encoderName) : MediaCodec.createEncoderByType(codecMime);
    } catch (IOException e) {
      // 优选/指定编码器实例化失败，退回按类型默认编码器
      encedec = MediaCodec.createEncoderByType(codecMime);
      codecInfo = resolveCodecInfo(null, codecMime);
    }
    // 对齐 scrcpy v4.1 size-constraints 修复：按编码器真实能力约束 videoSize，避免 configure 失败
    clampVideoSizeToEncoder(codecInfo);
    encodecFormat = new MediaFormat();
    encodecFormat.setString(MediaFormat.KEY_MIME, codecMime);
    encodecFormat.setInteger(MediaFormat.KEY_BIT_RATE, Options.maxVideoBit);
    currentBitrate = Options.maxVideoBit; // 自适应模式从封顶起步，由客户端向下收敛
    encodecFormat.setInteger(MediaFormat.KEY_FRAME_RATE, Options.maxFps);
    encodecFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) encodecFormat.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, Options.maxFps * 3);
    encodecFormat.setFloat("max-fps-to-encoder", Options.maxFps);
    encodecFormat.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 50_000);
    encodecFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
  }

  // 初始化编码器
  private static Surface surface;

  public static void startEncode() throws InvocationTargetException, NoSuchMethodException, IllegalAccessException, IOException, ErrnoException {
    ControlPacket.sendVideoSizeEvent();
    encodecFormat.setInteger(MediaFormat.KEY_WIDTH, Device.videoSize.first);
    encodecFormat.setInteger(MediaFormat.KEY_HEIGHT, Device.videoSize.second);
    try {
      encedec.configure(encodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    } catch (IllegalStateException e) {
      // v4.1 兜底：约束对齐后仍 configure 失败，进一步回退到编码器支持尺寸重试一次，避免启动直接崩
      applyEncoderFallbackSize();
      encodecFormat.setInteger(MediaFormat.KEY_WIDTH, Device.videoSize.first);
      encodecFormat.setInteger(MediaFormat.KEY_HEIGHT, Device.videoSize.second);
      encedec.configure(encodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }
    // 绑定Display和Surface
    surface = encedec.createInputSurface();
    setDisplaySurface(display, surface);
    // 启动编码
    encedec.start();
  }

  public static void stopEncode() {
    encedec.stop();
    encedec.reset();
    surface.release();
  }

  private static void setDisplaySurface(IBinder display, Surface surface) throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
    SurfaceControl.openTransaction();
    try {
      SurfaceControl.setDisplaySurface(display, surface);
      SurfaceControl.setDisplayProjection(display, 0, new Rect(0, 0, Device.displayInfo.width, Device.displayInfo.height), new Rect(0, 0, Device.videoSize.first, Device.videoSize.second));
      SurfaceControl.setDisplayLayerStack(display, Device.displayInfo.layerStack);
    } finally {
      SurfaceControl.closeTransaction();
    }
  }

  private static final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

  public static void encodeOut() throws IOException {
    try {
      // 找到已完成的输出缓冲区
      // 使用有限超时(1s)而非-1无限等待：关闭时线程可在超时后检测中断标志优雅退出，避免阻塞在 native 调用里只能靠 Runtime.exit(0)
      int outIndex;
      do {
        outIndex = encedec.dequeueOutputBuffer(bufferInfo, 1000);
        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          // 输出格式变化（首帧前）：把编解码器特定数据(csd-0 / AVC 的 csd-1)作为视频帧发给客户端，
          // 使协议自洽（此前服务端未发 csd，客户端读取错位）；VP8/VP9 通常无 csd，自然跳过
          sendCodecSpecificData();
        }
      } while (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED);
      if (outIndex < 0) return; // INFO_TRY_AGAIN_LATER：本轮无输出，直接返回由调用方循环重试
      encodedFrameCount++; // 统计：成功编码出一帧
      ByteBuffer buffer = encedec.getOutputBuffer(outIndex);
      if (buffer == null) return;
      ControlPacket.sendVideoEvent(bufferInfo.presentationTimeUs, buffer);
      encedec.releaseOutputBuffer(outIndex, false);
    } catch (IllegalStateException ignored) {
    }
  }

  /**
   * 输出格式变更时，把 csd（codec-specific data）发给客户端。
   * 仅首帧前发一次：AVC 需 csd-0 + csd-1（SPS/PPS），HEVC 只需 csd-0（含 VPS/SPS/PPS），VP8/VP9 无 csd。
   * 复用视频帧封装（sendVideoEvent），客户端以相同 readFrameFromVideo 读取并跳过 8 字节 pts。
   */
  private static void sendCodecSpecificData() {
    if (csdSent) return;
    try {
      MediaFormat format = encedec.getOutputFormat();
      ByteBuffer csd0 = format.getByteBuffer("csd-0");
      if (csd0 != null) ControlPacket.sendVideoEvent(0, csd0);
      if (codecTypeId == 0) { // 仅 AVC(H264) 需要 csd-1
        ByteBuffer csd1 = format.getByteBuffer("csd-1");
        if (csd1 != null) ControlPacket.sendVideoEvent(0, csd1);
      }
      csdSent = true;
    } catch (Exception ignored) {
    }
  }

  public static void release() {
    try {
      stopEncode();
      encedec.release();
      SurfaceControl.destroyDisplay(display);
    } catch (Exception ignored) {
    }
  }

  /**
   * 自适应码率：客户端按链路RTT动态请求的目标码率(bps)。
   * 限制在 [下限, maxVideoBit封顶] 区间内，仅当与当前值不同才调用 setParameters，
   * 并用 try/catch 兜底（编码器未在正确状态时不抛异常中断主流程）。
   */
  public static int getCurrentBitrate() {
    return currentBitrate;
  }

  public static void requestBitrate(int bitrate) {
    if (encedec == null) return;
    int clamped = Math.max(AUTO_BITRATE_FLOOR, Math.min(bitrate, Options.maxVideoBit));
    if (clamped == currentBitrate) return;
    try {
      // 运行时动态改码率：走公开的 Bundle 参数接口（MediaCodec.Parameters 为隐藏类，
      // 不在公开 SDK 中，编译期不可见）。KEY_BIT_RATE 即创建编码器时用的同一个码率键。
      Bundle params = new Bundle();
      params.putInt(MediaFormat.KEY_BIT_RATE, clamped);
      encedec.setParameters(params);
      currentBitrate = clamped;
    } catch (IllegalStateException ignored) {
    }
  }

  // ===== 以下为 v4.1 size-constraints 对齐辅助方法 =====

  /** 按编码器名（或 mime）从 MediaCodecList 解析 MediaCodecInfo，兼容 Android 9（不依赖 MediaCodec.getCodecInfo()） */
  private static MediaCodecInfo resolveCodecInfo(String encoderName, String mime) {
    MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
    if (encoderName != null) {
      for (MediaCodecInfo info : list.getCodecInfos()) {
        if (info.isEncoder() && encoderName.equals(info.getName())) return info;
      }
    }
    for (MediaCodecInfo info : list.getCodecInfos()) {
      if (!info.isEncoder()) continue;
      for (String type : info.getSupportedTypes()) {
        if (type.equalsIgnoreCase(mime)) return info;
      }
    }
    return null;
  }

  /**
   * 对齐 scrcpy v4.1 size-constraints 修复：把 videoSize 对齐到所选编码器的 VideoCapabilities 约束
   * ——width/height alignment 与支持的宽高上界，避免部分设备 configure 阶段因分辨率不被编码器支持而失败。
   */
  private static void clampVideoSizeToEncoder(MediaCodecInfo codecInfo) {
    if (codecInfo == null) return;
    try {
      MediaCodecInfo.VideoCapabilities vc = codecInfo.getCapabilitiesForType(codecMime).getVideoCapabilities();
      if (vc == null) return;
      int w = Device.videoSize.first;
      int h = Device.videoSize.second;
      int wa = vc.getWidthAlignment();
      int ha = vc.getHeightAlignment();
      if (wa > 1) w = ((w + wa / 2) / wa) * wa;
      if (ha > 1) h = ((h + ha / 2) / ha) * ha;
      int maxW = vc.getSupportedWidths().getUpper();
      int maxH = vc.getSupportedHeights().getUpper();
      // 等比缩回到支持的宽高上界内
      while ((w > maxW || h > maxH) && w > wa && h > ha) {
        if ((long) w * maxH > (long) h * maxW) w = (int) ((long) w * maxH / h);
        else h = (int) ((long) h * maxW / w);
        if (wa > 1) w = ((w + wa / 2) / wa) * wa;
        if (ha > 1) h = ((h + ha / 2) / ha) * ha;
      }
      if (w > 0 && h > 0) Device.videoSize = new Pair<>(w, h);
    } catch (Exception ignored) {
      // 查询失败保持原值，不阻断启动
    }
  }

  /**
   * configure 失败兜底：取编码器支持的宽高上界（按 alignment 对齐）作为最终尺寸重试一次，
   * 确保设备至少能启动编码（画面可能轻微拉伸，但服务不中断）。
   */
  private static void applyEncoderFallbackSize() {
    try {
      MediaCodecInfo codecInfo = resolveCodecInfo(encedec != null ? encedec.getName() : null, codecMime);
      if (codecInfo == null) return;
      MediaCodecInfo.VideoCapabilities vc = codecInfo.getCapabilitiesForType(codecMime).getVideoCapabilities();
      if (vc == null) return;
      int maxW = vc.getSupportedWidths().getUpper();
      int maxH = vc.getSupportedHeights().getUpper();
      int wa = vc.getWidthAlignment();
      int ha = vc.getHeightAlignment();
      int w = (wa > 1) ? (maxW / wa) * wa : maxW;
      int h = (ha > 1) ? (maxH / ha) * ha : maxH;
      int origW = Device.videoSize.first;
      int origH = Device.videoSize.second;
      // 不要超过原请求尺寸太多（避免码率爆炸），等比回退到原请求的 1.5 倍内
      while ((w > origW * 1.5 || h > origH * 1.5) && w > wa && h > ha) {
        if ((long) w * origH > (long) h * origW) w = (int) ((long) w * origH / h);
        else h = (int) ((long) h * origW / w);
        if (wa > 1) w = (w / wa) * wa;
        if (ha > 1) h = (h / ha) * ha;
      }
      if (w > 0 && h > 0) Device.videoSize = new Pair<>(w, h);
    } catch (Exception ignored) {
    }
  }

}
