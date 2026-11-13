# Keep source line information for private retracing with the matching mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Libraries supply their reflection/JNI consumer rules. Do not keep the entire app.
# Media3 loads FfmpegAudioRenderer by reflection in release builds.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
