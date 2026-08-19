/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package qzrs.Scrcpy.server.helper;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.system.ErrnoException;
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
  private static boolean useH265;

  private static IBinder display;

  // 自适应码率下限(bps)，避免画质崩塌；当前生效码率，用于去重避免重复 setParameters
  private static final int AUTO_BITRATE_FLOOR = 1_000_000;
  private static int currentBitrate = 0;
  // 已编码输出帧数（用于向客户端上报采集帧率）
  public static volatile long encodedFrameCount = 0;

  public static void init() throws InvocationTargetException, NoSuchMethodException, IllegalAccessException, IOException, ErrnoException {
    useH265 = Options.supportH265 && EncodecTools.isSupportH265();
    ByteBuffer byteBuffer = ByteBuffer.allocate(9);
    byteBuffer.put((byte) (useH265 ? 1 : 0));
    byteBuffer.putInt(Device.videoSize.first);
    byteBuffer.putInt(Device.videoSize.second);
    byteBuffer.flip();
    Server.writeVideo(byteBuffer);
    // 创建显示器
    display = SurfaceControl.createDisplay("scrcpy", Build.VERSION.SDK_INT < Build.VERSION_CODES.R || (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S".equals(Build.VERSION.CODENAME)));
    // 创建Codec
    createEncodecFormat();
    startEncode();
  }

  private static void createEncodecFormat() throws IOException {
    String codecMime = useH265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
    // 对齐 scrcpy v4.1：优先选用硬件编码器，避免部分机型默认编码器出绿屏/紫屏/卡顿；支持手动指定
    String encoderName = EncodecTools.selectEncoderName(codecMime, Options.videoEncoder);
    try {
      encedec = encoderName != null ? MediaCodec.createByCodecName(encoderName) : MediaCodec.createEncoderByType(codecMime);
    } catch (IOException e) {
      // 优选/指定编码器实例化失败，退回按类型默认编码器
      encedec = MediaCodec.createEncoderByType(codecMime);
    }
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
    encedec.configure(encodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
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

}
