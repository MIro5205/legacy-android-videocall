-keep class com.x10call.** { *; }
-keepclassmembers class com.x10call.MainActivity {
    public void onPreviewFrame(byte[], android.hardware.Camera);
}
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
