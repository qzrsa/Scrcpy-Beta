/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package qzrs.Scrcpy.server.entity;

public final class Options {
  public static int serverPort=25166;
  public static boolean listenerClip=true;
  public static boolean isAudio = true;
  public static int maxSize = 1600;
  public static int maxVideoBit = 4000000;
  // 自适应码率开关：开启时 maxVideoBit 作为封顶值，由客户端按RTT动态下发 setBitrate 调整
  public static boolean autoBitrate = false;
  public static int maxFps = 60;
  public static boolean keepAwake = true;
  public static boolean supportH265 = true;
  public static boolean supportOpus = true;
  // 编码器指定：空串表示自动优选硬件编码器（对齐 scrcpy v4.1 --video-encoder / --audio-encoder）
  public static String videoEncoder = "";
  public static String audioEncoder = "";
  public static String startApp = "";

  public static void parse(String... args) {
    for (String arg : args) {
      int equalIndex = arg.indexOf('=');
      if (equalIndex == -1) throw new IllegalArgumentException("参数格式错误");
      String key = arg.substring(0, equalIndex);
      String value = arg.substring(equalIndex + 1);
      switch (key) {
        case "serverPort":
          serverPort = Integer.parseInt(value);
          break;
        case "listenerClip":
          listenerClip = Integer.parseInt(value) == 1;
          break;
        case "isAudio":
          isAudio = Integer.parseInt(value) == 1;
          break;
        case "maxSize":
          maxSize = Integer.parseInt(value);
          break;
        case "maxFps":
          maxFps = Integer.parseInt(value);
          break;
        case "maxVideoBit":
          maxVideoBit = Integer.parseInt(value) * 1000000;
          break;
        case "autoBitrate":
          autoBitrate = Integer.parseInt(value) == 1;
          break;
        case "keepAwake":
          keepAwake = Integer.parseInt(value) == 1;
          break;
        case "supportH265":
          supportH265 = Integer.parseInt(value) == 1;
          break;
        case "supportOpus":
          supportOpus = Integer.parseInt(value) == 1;
          break;
        case "videoEncoder":
          // 仅允许合法编码器名（字母数字 . _ -），避免注入；空串表示自动优选
          if (value.isEmpty() || value.matches("[A-Za-z0-9._-]+")) videoEncoder = value;
          break;
        case "audioEncoder":
          if (value.isEmpty() || value.matches("[A-Za-z0-9._-]+")) audioEncoder = value;
          break;
        case "startApp":
          // 仅允许合法 Android 包名，杜绝 monkey -p / am stack list 中的 shell 与正则注入；空串表示不启动应用
          if (value.isEmpty() || value.matches("[a-zA-Z0-9._]+")) startApp = value;
          break;
      }
    }
  }
}

