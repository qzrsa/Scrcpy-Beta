package qzrs.Scrcpy.client.decode;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.util.Pair;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;

import qzrs.Scrcpy.client.tools.StatsOverlay;

public class VideoDecode {
  private MediaCodec decodec;
  private final StatsOverlay statsOverlay;
  // 渲染帧率统计
  private long renderFrameCount = 0;
  private long renderLastUpdate = System.currentTimeMillis();

  private final MediaCodec.Callback callback = new MediaCodec.Callback() {
    @Override
    public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int inIndex) {
      intputBufferQueue.offer(inIndex);
    }

    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int outIndex, @NonNull MediaCodec.BufferInfo bufferInfo) {
      try {
        mediaCodec.releaseOutputBuffer(outIndex, bufferInfo.presentationTimeUs);
        // 统计渲染帧率（每秒聚合一次上报）
        renderFrameCount++;
        long now = System.currentTimeMillis();
        long elapsed = now - renderLastUpdate;
        if (elapsed >= 1000) {
          int fps = (int) (renderFrameCount * 1000L / elapsed);
          renderFrameCount = 0;
          renderLastUpdate = now;
          if (statsOverlay != null) statsOverlay.onRenderFps(fps);
        }
      } catch (IllegalStateException ignored) {
      }
    }

    @Override
    public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
    }

    @Override
    public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat format) {
    }
  };

  public VideoDecode(int codecTypeId, Pair<Integer, Integer> videoSize, Surface surface, ByteBuffer csd0, ByteBuffer csd1, Handler playHandler, StatsOverlay statsOverlay) throws IOException, InterruptedException {
    this.statsOverlay = statsOverlay;
    setVideoDecodec(codecTypeId, videoSize, surface, csd0, csd1, playHandler);
  }

  public void release() {
    try {
      decodec.stop();
      decodec.release();
    } catch (Exception ignored) {
    }
  }

  private final LinkedBlockingQueue<Integer> intputBufferQueue = new LinkedBlockingQueue<>();

  public void decodeIn(ByteBuffer data) throws InterruptedException {
    try {
      long pts = data.getLong();
      int len = data.remaining();
      int inIndex = intputBufferQueue.take();
      decodec.getInputBuffer(inIndex).put(data);
      decodec.queueInputBuffer(inIndex, 0, len, pts, 0);
    } catch (IllegalStateException ignored) {
    }
  }

  // 创建Codec（codecTypeId: 0=H264 1=H265 2=VP8 3=VP9）
  private void setVideoDecodec(int codecTypeId, Pair<Integer, Integer> videoSize, Surface surface, ByteBuffer csd0, ByteBuffer csd1, Handler playHandler) throws IOException, InterruptedException {
    String codecMime;
    String codecLabel;
    switch (codecTypeId) {
      case 1: codecMime = MediaFormat.MIMETYPE_VIDEO_HEVC; codecLabel = "H265"; break;
      case 2: codecMime = MediaFormat.MIMETYPE_VIDEO_VP8; codecLabel = "VP8"; break;
      case 3: codecMime = MediaFormat.MIMETYPE_VIDEO_VP9; codecLabel = "VP9"; break;
      default: codecMime = MediaFormat.MIMETYPE_VIDEO_AVC; codecLabel = "H264"; break;
    }
    if (statsOverlay != null) {
      statsOverlay.setSource(videoSize.first + "x" + videoSize.second + "/" + codecLabel);
      statsOverlay.setDecodeMethod("硬解");
    }
    // 创建解码器
    try {
      String codecName = DecodecTools.getVideoDecoder(codecTypeId);
      if (Objects.equals(codecName, "")) decodec = MediaCodec.createDecoderByType(codecMime);
      else decodec = MediaCodec.createByCodecName(codecName);
    } catch (Exception ignord) {
      decodec = MediaCodec.createDecoderByType(codecMime);
    }
    MediaFormat decodecFormat = MediaFormat.createVideoFormat(codecMime, videoSize.first, videoSize.second);
    // 设置编解码器特定数据：仅 AVC(H264) 同时需要 csd-0 与 csd-1；HEVC 仅 csd-0；VP8/VP9 无 csd（已跳过读取）
    if (csd0 != null) {
      csd0.position(8); // 跳过 8 字节 pts
      decodecFormat.setByteBuffer("csd-0", csd0);
    }
    if (codecTypeId == 0 && csd1 != null) {
      csd1.position(8);
      decodecFormat.setByteBuffer("csd-1", csd1);
    }
    // 异步解码
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && playHandler != null) {
      decodec.setCallback(callback, playHandler);
    } else decodec.setCallback(callback);
    // 配置解码器
    decodec.configure(decodecFormat, surface, null, 0);
    // 启动解码器
    decodec.start();
    // 注意：不再把 csd 当首帧喂入（旧实现把 csd 数据当视频帧喂回，属协议错位 hack）；
    // 真实首帧由 ClientPlayer 的循环 readFrameFromVideo 读取后喂入
  }

}
