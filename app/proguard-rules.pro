# Keep Android framework components declared in AndroidManifest.xml
-keep public class com.holos.reader.ui.** extends android.app.Activity
-keep public class com.holos.reader.tts.** extends android.app.Activity
-keep public class com.holos.reader.tts.** extends android.app.Service
-keep public class com.holos.reader.update.ApkFileProvider extends android.content.ContentProvider

# ONNX Runtime JNI bindings
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Preserve Kotlin metadata & reflection-safe enums/data classes if needed
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
