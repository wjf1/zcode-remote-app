# Sprint 1 / P0-C：编译期剥离日志调用（与 util/ZLog 的 BuildConfig.DEBUG 门控双保险）。
# w/i/d/v 整体移除（含字符串参数），e 保留但 ZLog.e 调用点约定只写元信息。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
# 对 ZLog 本体也做调用点剥离：-keep 自家代码会阻止 R8 删除 ZLog.i(...) 调用点，
# 导致 "ws recv " 之类消息模板残留在 dex 常量池（虽不打印但没必要留）。
-assumenosideeffects class com.zcode.remote.util.ZLog {
    public static void d(...);
    public static void i(...);
    public static void w(...);
}

# 本轮保守策略：keep 自家代码，R8 仅做第三方库裁剪 + 日志剥离；
# 混淆改名待真机冒烟验证后再放开（本机无模拟器，无法预先验证反射/序列化链路）。
-keep class com.zcode.remote.** { *; }

# kotlinx.serialization 官方推荐规则（放开自家 keep 后仍有效）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.zcode.remote.**$$serializer { *; }
-keepclassmembers class com.zcode.remote.** {
    *** Companion;
}
-keepclasseswithmembers class com.zcode.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn okhttp3.**
-dontwarn okio.**
