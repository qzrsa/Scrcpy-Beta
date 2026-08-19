package qzrs.Scrcpy.client.tools;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Pair;
import android.view.Surface;

import java.nio.ByteBuffer;

import qzrs.Scrcpy.client.Client;
import qzrs.Scrcpy.client.decode.AudioDecode;
import qzrs.Scrcpy.client.decode.VideoDecode;
import qzrs.Scrcpy.helper.PublicTools;

public class ClientPlayer {
  private boolean isClose = false;
  private final ClientController clientController;
  private final ClientStream clientStream;
  private final StatsOverlay statsOverlay;
  private final Thread mainStreamInThread = new Thread(this::mainStreamIn);
  private final Thread videoStreamInThread = new Thread(this::videoStreamIn);
  private Handler playHandler = null;
  private final HandlerThread playHandlerThread = new HandlerThread("scrcpy_play", Thread.MAX_PRIORITY);
  private static final int AUDIO_EVENT = 1;
  private static final int CLIPBOARD_EVENT = 2;
  private static final int CHANGE_SIZE_EVENT = 3;

  public ClientPlayer(String uuid, ClientStream clientStream) {
    clientController = Client.getClientController(uuid);
    this.clientStream = clientStream;
    statsOverlay = clientStream.getStatsOverlay();
    if (clientController == null) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      playHandlerThread.start();
      playHandler = new Handler(playHandlerThread.getLooper());
    }
    mainStreamInThread.start();
    videoStreamInThread.start();
  }

  private void mainStreamIn() {
    AudioDecode audioDecode = null;
    boolean useOpus = true;
    try {
      if (clientStream.readByteFromMain() == 1) useOpus = clientStream.readByteFromMain() == 1;
      while (!Thread.interrupted()) {
        switch (clientStream.readByteFromMain()) {
          case AUDIO_EVENT:
            ByteBuffer audioFrame = clientStream.readFrameFromMain();
            if (audioDecode != null) audioDecode.decodeIn(audioFrame);
            else audioDecode = new AudioDecode(useOpus, audioFrame, playHandler);
            break;
          case CLIPBOARD_EVENT:
            clientController.handleAction("setClipBoard", clientStream.readByteArrayFromMain(clientStream.readIntFromMain()), 0);
            break;
          case CHANGE_SIZE_EVENT:
            clientController.handleAction("updateVideoSize", clientStream.readByteArrayFromMain(8), 0);
            break;
          case 4:
            // 收到心跳回复，计算真实RTT往返延迟
            if (clientStream.pingSendTime != 0) {
              long rtt = System.currentTimeMillis() - clientStream.pingSendTime;
              statsOverlay.onLatency(rtt);
              clientStream.lastRtt = rtt; // 供自适应码率控制器读取
              clientStream.pingSendTime = 0;
            }
            break;
          case 11:
            // 服务端统计：视频采集帧率 + 当前码率
            statsOverlay.onCaptureFps(clientStream.readIntFromMain());
            statsOverlay.setBitrate(clientStream.readIntFromMain());
            break;
        }
      }
    } catch (InterruptedException ignored) {
    } catch (Exception e) {
      PublicTools.logToast("player", e.toString(), false);
    } finally {
      if (audioDecode != null) audioDecode.release();
    }
  }

  private void videoStreamIn() {
    VideoDecode videoDecode = null;
    try {
      // 协商编码类型：0=H264 1=H265 2=VP8 3=VP9（与服务端 VideoEncode.init 发出的枚举一致）
      int codecTypeId = clientStream.readByteFromVideo();
      Pair<Integer, Integer> videoSize = new Pair<>(clientStream.readIntFromVideo(), clientStream.readIntFromVideo());
      Surface surface = new Surface(clientController.getTextureView().getSurfaceTexture());
      ByteBuffer csd0 = null;
      ByteBuffer csd1 = null;
      if (codecTypeId == 0) {
        // H264：csd-0 + csd-1
        csd0 = clientStream.readFrameFromVideo();
        csd1 = clientStream.readFrameFromVideo();
      } else if (codecTypeId == 1) {
        // H265：仅 csd-0
        csd0 = clientStream.readFrameFromVideo();
      }
      // VP8(2)/VP9(3)：无 csd，不读取
      videoDecode = new VideoDecode(codecTypeId, videoSize, surface, csd0, csd1, playHandler, statsOverlay);
      while (!Thread.interrupted()) {
        ByteBuffer frame = clientStream.readFrameFromVideo();
        if (statsOverlay != null) statsOverlay.onVideoFrame(frame.remaining());
        videoDecode.decodeIn(frame);
      }
    } catch (Exception ignored) {
    } finally {
      if (videoDecode != null) videoDecode.release();
    }
  }

  public void close() {
    if (isClose) return;
    isClose = true;
    if (statsOverlay != null) statsOverlay.hide();
    mainStreamInThread.interrupt();
    videoStreamInThread.interrupt();
    playHandlerThread.interrupt();
  }
}
