# Called from native code (kamhost.c / innoextract_jni.cpp) by name: keep JNI natives and callbacks
-keep class org.kamremake.android.KaMNative { *; }
-keep class org.kamremake.android.KaMBackupAgent { *; }
-keep class org.kamremake.android.InnoExtract { *; }
-keep interface org.kamremake.android.InnoExtract$FileCallback { *; }
# libVLC uses JNI and reflection internally
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
-dontwarn org.videolan.**
