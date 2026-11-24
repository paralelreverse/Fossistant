# Keep LiteRT-LM JNI and @Tool / @ToolParam reflection metadata for Gemma 4 E2B QAT tool calling
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class org.fossistant.assistant.engine.FossistantGemmaToolSet { *; }
-keep class org.fossistant.assistant.engine.FossistantGemmaToolSet$* { *; }
-keepclassmembers class * {
    @com.google.ai.edge.litertlm.Tool <methods>;
}

# Keep callbacks and native methods called from JNI (libwhisper_jni.so and libsd_jni.so)
-keep class org.fossistant.assistant.engine.ImageGenEngine$StepListener {
    void onStep(int, int);
}
-keepclasseswithmembers class * {
    native <methods>;
}

# Keep Kotlinx Serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
