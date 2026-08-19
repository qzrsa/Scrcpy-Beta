/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package qzrs.Scrcpy.server.helper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import qzrs.Scrcpy.server.Server;
import qzrs.Scrcpy.server.entity.Device;

public final class ControlPacket {

  // 复用缓冲：避免每帧 ByteBuffer.allocate 造成 GC 抖动。
  // 视频帧只由视频线程写入、音频帧只由音频线程写入，各自独立，无需同步。
  private static byte[] videoSendBuf = new byte[0];
  private static ByteBuffer videoSendBB = ByteBuffer.wrap(videoSendBuf);
  private static byte[] audioSendBuf = new byte[0];
  private static ByteBuffer audioSendBB = ByteBuffer.wrap(audioSendBuf);

  public static void sendVideoEvent(long pts, ByteBuffer data) throws IOException {
    int dataLen = data.remaining();
    int size = dataLen + 8;
    if (size < 8) return;
    int total = 4 + size;
    if (videoSendBuf.length < total) {
      videoSendBuf = new byte[total];
      videoSendBB = ByteBuffer.wrap(videoSendBuf);
    }
    videoSendBB.clear();
    videoSendBB.putInt(size);
    videoSendBB.putLong(pts);
    videoSendBB.put(data);
    videoSendBB.flip();
    Server.writeVideo(videoSendBB);
  }

  public static void sendAudioEvent(ByteBuffer data) throws IOException {
    int size = data.remaining();
    if (size < 0) return;
    int total = 5 + size;
    if (audioSendBuf.length < total) {
      audioSendBuf = new byte[total];
      audioSendBB = ByteBuffer.wrap(audioSendBuf);
    }
    audioSendBB.clear();
    audioSendBB.put((byte) 1);
    audioSendBB.putInt(size);
    audioSendBB.put(data);
    audioSendBB.flip();
    Server.writeMain(audioSendBB);
  }

  public static void sendClipboardEvent(String newClipboardText) {
    byte[] tmpTextByte = newClipboardText.getBytes(StandardCharsets.UTF_8);
    if (tmpTextByte.length == 0 || tmpTextByte.length > 5000) return;
    ByteBuffer byteBuffer = ByteBuffer.allocate(5 + tmpTextByte.length);
    byteBuffer.put((byte) 2);
    byteBuffer.putInt(tmpTextByte.length);
    byteBuffer.put(tmpTextByte);
    byteBuffer.flip();
    try {
      Server.writeMain(byteBuffer);
    } catch (IOException e) {
      Server.errorClose(e);
    }
  }

  public static void sendVideoSizeEvent() throws IOException {
    ByteBuffer byteBuffer = ByteBuffer.allocate(9);
    byteBuffer.put((byte) 3);
    byteBuffer.putInt(Device.videoSize.first);
    byteBuffer.putInt(Device.videoSize.second);
    byteBuffer.flip();
    Server.writeMain(byteBuffer);
  }

  /** 统计信息包(type=11)：视频采集帧率 + 当前码率(bps)，供客户端展示 */
  public static ByteBuffer createStats(int captureFps, int bitrate) {
    ByteBuffer byteBuffer = ByteBuffer.allocate(9);
    byteBuffer.put((byte) 11);
    byteBuffer.putInt(captureFps);
    byteBuffer.putInt(bitrate);
    byteBuffer.flip();
    return byteBuffer;
  }

  public static void handleTouchEvent() throws IOException {
    int action = Server.mainInputStream.readByte();
    int pointerId = Server.mainInputStream.readByte();
    float x = Server.mainInputStream.readFloat();
    float y = Server.mainInputStream.readFloat();
    int offsetTime = Server.mainInputStream.readInt();
    Device.touchEvent(action, x, y, pointerId, offsetTime);
  }

  public static void handleKeyEvent() throws IOException {
    int keyCode = Server.mainInputStream.readInt();
    int meta = Server.mainInputStream.readInt();
    Device.keyEvent(keyCode, meta);
  }

  public static void handleClipboardEvent() throws IOException {
    int size = Server.mainInputStream.readInt();
    byte[] textBytes = new byte[size];
    Server.mainInputStream.readFully(textBytes);
    String text = new String(textBytes, StandardCharsets.UTF_8);
    Device.setClipboardText(text);
  }

}

