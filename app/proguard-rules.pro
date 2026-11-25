# yt-dlp wrapper (youtubedl-android): parses yt-dlp's JSON with Jackson via reflection, which R8 otherwise breaks
# ("class … is not a concrete class" at first use).
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn com.fasterxml.jackson.**
# It unpacks Python with Apache Commons Compress, which registers its zip extra fields by reflection.
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
