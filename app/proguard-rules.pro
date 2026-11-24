# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 保留泛型签名、内部类与行号信息，防止 Release 模式下反射泛型 ViewBinding 崩溃并保留堆栈信息
-keepattributes Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# FreeReflection
-keep class me.weishu.reflection.** {*;}

-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    *** inflate(android.view.LayoutInflater);
}

-keep class * extends android.app.Activity
-keep class * implements androidx.viewbinding.ViewBinding {
    <init>();
    *** inflate(android.view.LayoutInflater);
}

# Okio uses this optional nullability annotation for static analysis.
-dontwarn javax.annotation.Nullable

# 保留 DexKit JNI 桥接类，防止 Release 混淆导致 native 调用失败
-keep class org.luckypray.dexkit.** { *; }
