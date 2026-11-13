# Keep source line information for private retracing with the matching mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Libraries supply their reflection/JNI consumer rules. Do not keep the entire app.
# Media3 loads its FFmpeg audio renderer via reflection; release R8 must not
# remove or rename it. This is audio-only: video stays on the hardware renderer.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
