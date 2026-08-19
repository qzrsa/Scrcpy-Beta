# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# 仅保留 manifest 入口组件与广播接收器，其余由 R8 做可达性分析自动缩减/混淆
# （AGP 也会自动保留 manifest 组件，这里显式写出便于维护）
-keep public class qzrs.Scrcpy.MainActivity
-keep public class qzrs.Scrcpy.DeviceDetailActivity
-keep public class qzrs.Scrcpy.SetActivity
-keep public class qzrs.Scrcpy.IpActivity
-keep public class qzrs.Scrcpy.AdbKeyActivity
-keep public class qzrs.Scrcpy.ActiveActivity
-keep public class qzrs.Scrcpy.UsbActivity
-keep public class qzrs.Scrcpy.client.view.FullActivity
-keep class qzrs.Scrcpy.helper.MyBroadcastReceiver