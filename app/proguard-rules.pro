# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.dockermobile.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.dockermobile.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.dockermobile.app.**$$serializer { *; }

# SnakeYAML (reflection based YAML parsing for compose.yaml)
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
