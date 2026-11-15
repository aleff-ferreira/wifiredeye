# Keep kotlinx.serialization generated serializers for diagnostic JSON export
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.a55.wifiradar.**$$serializer { *; }
-keepclassmembers class com.a55.wifiradar.** { *** Companion; }
-keepclasseswithmembers class com.a55.wifiradar.** { kotlinx.serialization.KSerializer serializer(...); }
