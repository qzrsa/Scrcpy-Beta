package qzrs.Scrcpy.client.decode;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.util.ArrayList;
import java.util.Objects;

public class DecodecTools {
  private static ArrayList<String> hevcDecodecList = null;
  private static ArrayList<String> avcDecodecList = null;
  private static ArrayList<String> vp8DecodecList = null;
  private static ArrayList<String> vp9DecodecList = null;
  private static ArrayList<String> opusDecodecList = null;
  private static Boolean isSupportOpus = null;
  private static Boolean isSupportH265 = null;

  // 获取解码器列表
  private static void getDecodecList() {
    MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
    hevcDecodecList = new ArrayList<>();
    avcDecodecList = new ArrayList<>();
    opusDecodecList = new ArrayList<>();
    for (MediaCodecInfo mediaCodecInfo : mediaCodecList.getCodecInfos()) {
      if (!mediaCodecInfo.isEncoder()) {
        String codecName = mediaCodecInfo.getName();
        for (String supportType : mediaCodecInfo.getSupportedTypes()) {
          if (Objects.equals(supportType, MediaFormat.MIMETYPE_AUDIO_OPUS)) opusDecodecList.add(codecName);
          else {
            // 视频解码器要求硬件实现
            if (!codecName.startsWith("OMX.google") && !codecName.startsWith("c2.android")) {
              if (Objects.equals(supportType, MediaFormat.MIMETYPE_VIDEO_HEVC)) hevcDecodecList.add(codecName);
              else if (Objects.equals(supportType, MediaFormat.MIMETYPE_VIDEO_AVC)) avcDecodecList.add(codecName);
              else if (Objects.equals(supportType, MediaFormat.MIMETYPE_VIDEO_VP8)) vp8DecodecList.add(codecName);
              else if (Objects.equals(supportType, MediaFormat.MIMETYPE_VIDEO_VP9)) vp9DecodecList.add(codecName);
            }
          }
        }
      }
    }
  }

  // 获取解码器是否支持
  public static boolean isSupportOpus() {
    if (isSupportOpus != null) return isSupportOpus;
    if (opusDecodecList == null) getDecodecList();
    isSupportOpus = opusDecodecList.size() > 0;
    return isSupportOpus;
  }

  public static boolean isSupportH265() {
    if (isSupportH265 != null) return isSupportH265;
    if (hevcDecodecList == null) getDecodecList();
    isSupportH265 = hevcDecodecList.size() > 0;
    return isSupportH265;
  }

  public static boolean isSupportVP8() {
    if (vp8DecodecList == null) getDecodecList();
    return vp8DecodecList.size() > 0;
  }

  public static boolean isSupportVP9() {
    if (vp9DecodecList == null) getDecodecList();
    return vp9DecodecList.size() > 0;
  }

  // 获取视频最优解码器（codecTypeId: 0=H264 1=H265 2=VP8 3=VP9）
  public static String getVideoDecoder(int codecTypeId) {
    if (hevcDecodecList == null || avcDecodecList == null || vp8DecodecList == null || vp9DecodecList == null) getDecodecList();
    ArrayList<String> allHardNormalDecodec;
    switch (codecTypeId) {
      case 1: allHardNormalDecodec = hevcDecodecList; break;
      case 2: allHardNormalDecodec = vp8DecodecList; break;
      case 3: allHardNormalDecodec = vp9DecodecList; break;
      default: allHardNormalDecodec = avcDecodecList; break;
    }
    ArrayList<String> allHardLowLatencyDecodec = new ArrayList<>();
    for (String codecName : allHardNormalDecodec) if (codecName.contains("low_latency")) allHardLowLatencyDecodec.add(codecName);
    // 存在低延迟解码器
    if (allHardLowLatencyDecodec.size() > 0) return getC2Decodec(allHardLowLatencyDecodec);
    // 选择正常解码器
    if (allHardNormalDecodec.size() > 0) return getC2Decodec(allHardNormalDecodec);
    return "";
  }

  // 优选C2解码器
  private static String getC2Decodec(ArrayList<String> allHardDecodec) {
    for (String codecName : allHardDecodec) if (codecName.contains("c2")) return codecName;
    return allHardDecodec.get(0);
  }
}
