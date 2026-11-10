# Keep LiteRT-LM JNI and @Tool / @ToolParam reflection metadata for Gemma 4 E2B QAT tool calling
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class org.fossistant.assistant.engine.FossistantGemmaToolSet { *; }
-keepclassmembers class * {
    @com.google.ai.edge.litertlm.Tool <methods>;
}

# Keep Cactus Compute (Needle 3 & Whistle) JNI bindings
-keep class com.cactus.** { *; }
-keep class com.cactuscompute.** { *; }

# Keep Kotlinx Serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
