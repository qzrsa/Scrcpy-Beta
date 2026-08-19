package qzrs.Scrcpy.server.helper;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.util.ArrayList;
import java.util.Objects;

public class EncodecTools {
  private static ArrayList<String> hevcEncodecList = null;
  private static ArrayList<String> avcEncodecList = null;
  private static ArrayList<String> vp8EncodecList = null;
  private static ArrayList<String> vp9EncodecList = null;
  private static ArrayList<String> opusEncodecList = null;

  // 获取解码器列表
  private static void getEncodecList() {
    MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
    hevcEncodecList = new ArrayList<>();
    avcEncodecList = new ArrayList<>();
    vp8EncodecList = new ArrayList<>();
    vp9EncodecList = new ArrayList<>();
    opusEncodecList = new ArrayList<>();
    for (MediaCodecInfo mediaCodecInfo : mediaCodecList.getCodecInfos()) {
      if (mediaCodecInfo.isEncoder()) {
        String codecName = mediaCodecInfo.getName();
        if (codecName.toLowerCase().contains("opus")) opusEncodecList.add(codecName);
        // 要求硬件实现（软件编码器延迟高、部分机型画质差，与 selectEncoderName 一致）
        if (!codecName.startsWith("OMX.google") && !codecName.startsWith("c2.android")) {
          for (String supportType : mediaCodecInfo.getSupportedTypes()) {
            if (Objects.equals(supportType, MediaFormat.MIMETYPE_VIDEO_HEVC)) hevcEncodecList.add(codecName);
            else if (Objects.equals(supportType, MediaFormat.MIMETYPE_VIDEO_AVC)) avcEncodecList.add(codecName);
            else if (Objects.equals(supportType, MediaFormat.MIMETYPE_VIDEO_VP8)) vp8EncodecList.add(codecName);
            else if (Objects.equals(supportType, MediaFormat.MIMETYPE_VIDEO_VP9)) vp9EncodecList.add(codecName);
          }
        }
      }
    }
  }

  // 获取解码器是否支持
  public static boolean isSupportOpus() {
    if (opusEncodecList == null) getEncodecList();
    return opusEncodecList.size() > 0;
  }

  public static boolean isSupportAVC() {
    if (avcEncodecList == null) getEncodecList();
    return avcEncodecList.size() > 0;
  }

  public static boolean isSupportVP8() {
    if (vp8EncodecList == null) getEncodecList();
    return vp8EncodecList.size() > 0;
  }

  public static boolean isSupportVP9() {
    if (vp9EncodecList == null) getEncodecList();
    return vp9EncodecList.size() > 0;
  }

  public static boolean isSupportH265() {
    if (hevcEncodecList == null) getEncodecList();
    return hevcEncodecList.size() > 0;
  }

  /**
   * 对齐 Genymobile/scrcpy v4.1 的编码器选择策略：
   * - 优先选用硬件编码器，规避部分机型默认编码器出绿屏/紫屏/卡顿的问题；
   * - 支持通过 preferred 手动指定编码器名（与 v4.1 --video-encoder / --audio-encoder 一致）；
   * - 找不到合适的硬件编码器时退回软件编码器，最后返回 null 由调用方走 createEncoderByType 兜底。
   */
  public static String selectEncoderName(String mimeType, String preferred) {
    MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
    MediaCodecInfo fallback = null;
    for (MediaCodecInfo info : mediaCodecList.getCodecInfos()) {
      if (!info.isEncoder()) continue;
      boolean supported = false;
      for (String type : info.getSupportedTypes()) {
        if (type.equalsIgnoreCase(mimeType)) {
          supported = true;
          break;
        }
      }
      if (!supported) continue;
      String name = info.getName();
      // 手动指定优先：名字精确匹配即采用
      if (preferred != null && !preferred.isEmpty()) {
        if (name.equals(preferred)) return name;
        continue;
      }
      // 优先硬件编码器，跳过软件实现（OMX.google.* / c2.android.* 延迟高、部分机型画质差）
      if (name.startsWith("OMX.google") || name.startsWith("c2.android")) {
        if (fallback == null) fallback = info;
        continue;
      }
      return name; // 首个硬件编码器即采用
    }
    // 无硬件编码器（罕见）时退回软件编码器
    if (fallback != null) return fallback.getName();
    // 实在没找到则返回 null，由调用方 createEncoderByType 兜底
    return null;
  }

}
