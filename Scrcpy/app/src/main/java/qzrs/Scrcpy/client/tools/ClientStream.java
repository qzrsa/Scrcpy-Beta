package qzrs.Scrcpy.client.tools;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

import qzrs.Scrcpy.BuildConfig;
import qzrs.Scrcpy.R;
import qzrs.Scrcpy.adb.Adb;
import qzrs.Scrcpy.buffer.BufferStream;
import qzrs.Scrcpy.client.decode.DecodecTools;
import qzrs.Scrcpy.entity.AppData;
import qzrs.Scrcpy.entity.Device;
import qzrs.Scrcpy.entity.MyInterface;
import qzrs.Scrcpy.helper.PublicTools;

public class ClientStream {
  private boolean isClose = false;
  private boolean connectDirect = false;
  private Adb adb;
  private Socket mainSocket;
  private Socket videoSocket;
  private OutputStream mainOutputStream;
  private DataInputStream mainDataInputStream;
  private DataInputStream videoDataInputStream;
  private BufferStream mainBufferStream;
  private BufferStream videoBufferStream;
  private BufferStream shell;
  private Thread connectThread = null;
  private static final String serverName = "/data/local/tmp/scrcpy_server_" + BuildConfig.VERSION_CODE + ".jar";
  private static final boolean supportH265 = DecodecTools.isSupportH265();
  private static final boolean supportOpus = DecodecTools.isSupportOpus();

  private static final int timeoutDelay = 1000 * 15;

  // 自适应码率参数（单位 bps / ms）
  private static final int ADAPTIVE_CEILING_BPS = 12_000_000; // 封顶，与设备页"自适应"对应的上限一致
  private static final int ADAPTIVE_FLOOR_BPS = 1_000_000;    // 下限，避免画质崩塌
  private static final int ADAPTIVE_STEP_BPS = 1_000_000;     // 通畅时每周期加性递增步长
  private static final int ADAPTIVE_MIN_STEP_BPS = 500_000;   // 变化小于此值则不发送，避免抖动
  private static final long RTT_CONGESTED_MS = 150;           // 超过视为拥塞，乘性降码率
  private static final long RTT_HEALTHY_MS = 80;              // 低于视为通畅，加性升码率
  private static final long ADAPTIVE_INTERVAL_MS = 2000;      // 调整周期，与 keepalive(2s) 对齐

  private Thread adaptiveThread = null;

  // 统计信息覆盖层
  private final StatsOverlay statsOverlay = new StatsOverlay();

  // 心跳包发送时间戳，用于计算RTT
  public long pingSendTime = 0;

  // 自适应码率：最近一次测得的RTT(ms)，由 ClientPlayer 写入；controller 线程读取
  public volatile long lastRtt = 0;
  // 自适应码率开关（来自设备配置），为 true 时启动动态调整线程
  public boolean autoBitrate = false;

  // 复用缓冲，避免直连路径每帧 new byte[] 造成持续 GC 抖动；按需增长，不收缩
  private byte[] mainFrameBuffer = new byte[0];
  private byte[] videoFrameBuffer = new byte[0];

  public StatsOverlay getStatsOverlay() {
    return statsOverlay;
  }

  public ClientStream(Device device, MyInterface.MyFunctionBoolean handle) {
    this.autoBitrate = device.autoBitrate;
    Thread timeOutThread = new Thread(() -> {
      try {
        Thread.sleep(timeoutDelay);
        PublicTools.logToast("stream", AppData.applicationContext.getString(R.string.toast_timeout), true);
        handle.run(false);
        if (connectThread != null) connectThread.interrupt();
      } catch (InterruptedException ignored) {
      }
    });
    connectThread = new Thread(() -> {
      try {
        adb = AdbTools.connectADB(device);
        startServer(device);
        connectServer(device);
        statsOverlay.setDirect(connectDirect);
        if (autoBitrate) startAdaptiveBitrate();
        handle.run(true);
      } catch (Exception e) {
        PublicTools.logToast("stream", e.toString(), true);
        handle.run(false);
      } finally {
        timeOutThread.interrupt();
      }
    });
    connectThread.start();
    timeOutThread.start();
  }

  private void startServer(Device device) throws Exception {
    if (BuildConfig.ENABLE_DEBUG_FEATURE || !adb.runAdbCmd("ls /data/local/tmp/scrcpy_*").contains(serverName)) {
      adb.runAdbCmd("rm /data/local/tmp/scrcpy_* ");
      adb.pushFile(AppData.applicationContext.getResources().openRawResource(R.raw.scrcpy_server), serverName, null);
    }
    shell = adb.getShell();
    shell.write(ByteBuffer.wrap(("app_process -Djava.class.path=" + serverName + " / qzrs.Scrcpy.server.Server"
      + " serverPort=" + device.serverPort
      + " listenClip=" + (device.listenClip ? 1 : 0)
      + " isAudio=" + (device.isAudio ? 1 : 0)
      + " maxSize=" + device.maxSize
      + " maxFps=" + device.maxFps
      + " maxVideoBit=" + device.maxVideoBit
      + " autoBitrate=" + (device.autoBitrate ? 1 : 0)
      + " keepAwake=" + (device.keepWakeOnRunning ? 1 : 0)
      + " supportH265=" + ((device.useH265 && supportH265) ? 1 : 0)
      + " supportOpus=" + (supportOpus ? 1 : 0)
      + " startApp=" + device.startApp + " \n").getBytes()));
  }

  private void connectServer(Device device) throws Exception {
    Thread.sleep(50);
    int reTry = 40;
    int reTryTime = timeoutDelay / reTry;
    if (!device.isLinkDevice()) {
      long startTime = System.currentTimeMillis();
      boolean mainConn = false;
      InetSocketAddress inetSocketAddress = new InetSocketAddress(PublicTools.getIp(device.address), device.serverPort);
      for (int i = 0; i < reTry; i++) {
        try {
          if (!mainConn) {
            mainSocket = new Socket();
            mainSocket.connect(inetSocketAddress, timeoutDelay / 2);
            mainConn = true;
          }
          videoSocket = new Socket();
          videoSocket.connect(inetSocketAddress, timeoutDelay / 2);
          mainOutputStream = mainSocket.getOutputStream();
          mainDataInputStream = new DataInputStream(mainSocket.getInputStream());
          videoDataInputStream = new DataInputStream(videoSocket.getInputStream());
          connectDirect = true;
          return;
      } catch (Exception ignored) {
        // 关闭已建立的 socket 并复位状态，否则下一轮重试会复用已关闭的 mainSocket 导致直连失败
        if (mainSocket != null) {
          try {
            mainSocket.close();
          } catch (Exception e) {
          }
        }
        if (videoSocket != null) {
          try {
            videoSocket.close();
          } catch (Exception e) {
          }
        }
        mainSocket = null;
        videoSocket = null;
        mainConn = false;
        if (System.currentTimeMillis() - startTime >= timeoutDelay / 2 - 1000) i = reTry;
        else Thread.sleep(reTryTime);
      }
      }
    }
    for (int i = 0; i < reTry; i++) {
      try {
        if (mainBufferStream == null) mainBufferStream = adb.tcpForward(device.serverPort);
        if (videoBufferStream == null) videoBufferStream = adb.tcpForward(device.serverPort);
        return;
      } catch (Exception ignored) {
        Thread.sleep(reTryTime);
      }
    }
    throw new Exception(AppData.applicationContext.getString(R.string.toast_connect_server));
  }

  public String runShell(String cmd) throws Exception {
    return adb.runAdbCmd(cmd);
  }

  public byte readByteFromMain() throws IOException, InterruptedException {
    if (connectDirect) return mainDataInputStream.readByte();
    else return mainBufferStream.readByte();
  }

  public byte readByteFromVideo() throws IOException, InterruptedException {
    if (connectDirect) return videoDataInputStream.readByte();
    else return videoBufferStream.readByte();
  }

  public int readIntFromMain() throws IOException, InterruptedException {
    if (connectDirect) return mainDataInputStream.readInt();
    else return mainBufferStream.readInt();
  }

  public int readIntFromVideo() throws IOException, InterruptedException {
    if (connectDirect) return videoDataInputStream.readInt();
    else return videoBufferStream.readInt();
  }

  public ByteBuffer readByteArrayFromMain(int size) throws IOException, InterruptedException {
    if (connectDirect) {
      if (mainFrameBuffer.length < size) mainFrameBuffer = new byte[size];
      mainDataInputStream.readFully(mainFrameBuffer, 0, size);
      return ByteBuffer.wrap(mainFrameBuffer, 0, size);
    } else return mainBufferStream.readByteArray(size);
  }

  public ByteBuffer readByteArrayFromVideo(int size) throws IOException, InterruptedException {
    if (connectDirect) {
      if (videoFrameBuffer.length < size) videoFrameBuffer = new byte[size];
      videoDataInputStream.readFully(videoFrameBuffer, 0, size);
      return ByteBuffer.wrap(videoFrameBuffer, 0, size);
    }
    return videoBufferStream.readByteArray(size);
  }

  public ByteBuffer readFrameFromMain() throws Exception {
    if (!connectDirect) mainBufferStream.flush();
    return readByteArrayFromMain(readIntFromMain());
  }

  public ByteBuffer readFrameFromVideo() throws Exception {
    if (!connectDirect) videoBufferStream.flush();
    int size = readIntFromVideo();
    return readByteArrayFromVideo(size);
  }

  public void writeToMain(ByteBuffer byteBuffer) throws Exception {
    if (connectDirect) {
      // 按实际有效区间写入，避免复用缓冲后把数组尾部脏数据也发出去
      mainOutputStream.write(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(), byteBuffer.remaining());
    } else mainBufferStream.write(byteBuffer);
  }

  /**
   * 发送 keepAlive 并测量 RTT 延迟，结果上报给 StatsOverlay
   */
  public void writeToMainWithLatency(ByteBuffer byteBuffer) throws Exception {
    pingSendTime = System.currentTimeMillis();
    writeToMain(byteBuffer);
  }

  public void close() {
    if (isClose) return;
    isClose = true;
    if (adaptiveThread != null) adaptiveThread.interrupt();
    if (shell != null) PublicTools.logToast("server", new String(shell.readByteArrayBeforeClose().array()), false);
    if (connectDirect) {
      try {
        mainOutputStream.close();
        videoDataInputStream.close();
        mainDataInputStream.close();
        mainSocket.close();
        videoSocket.close();
      } catch (Exception ignored) {
      }
    } else {
      mainBufferStream.close();
      videoBufferStream.close();
    }
  }

  /**
   * 自适应码率控制器：连接建立后启动，按最近 RTT 用 AIMD 在 [下限, 封顶] 内动态调整码率。
   * 拥塞(RTT高)时乘性降、通畅(RTT低)时加性升，带滞后区间与最小步长防止抖动/忽高忽低。
   * 仅自适应模式(autoBitrate)启动，固定码率模式不生效。
   */
  private void startAdaptiveBitrate() {
    adaptiveThread = new Thread(() -> {
      int current = ADAPTIVE_CEILING_BPS; // 从封顶起步，由链路状况向下收敛
      while (!Thread.interrupted() && !isClose) {
        try {
          Thread.sleep(ADAPTIVE_INTERVAL_MS);
        } catch (InterruptedException e) {
          break;
        }
        long rtt = lastRtt;
        if (rtt <= 0) continue; // 尚未测到有效RTT，跳过
        int target;
        if (rtt > RTT_CONGESTED_MS) {
          target = (int) (current * 0.8); // 拥塞：乘性降
        } else if (rtt < RTT_HEALTHY_MS) {
          target = Math.min(ADAPTIVE_CEILING_BPS, current + ADAPTIVE_STEP_BPS); // 通畅：加性升
        } else {
          target = current; // 滞后区间：保持不变，避免边界抖动
        }
        target = Math.max(ADAPTIVE_FLOOR_BPS, Math.min(target, ADAPTIVE_CEILING_BPS));
        if (Math.abs(target - current) >= ADAPTIVE_MIN_STEP_BPS) {
          try {
            writeToMain(ControlPacket.createSetBitrate(target));
            current = target;
          } catch (Exception ignored) {
          }
        }
      }
    }, "scrcpy_adaptive");
    adaptiveThread.setDaemon(true);
    adaptiveThread.start();
  }
}
