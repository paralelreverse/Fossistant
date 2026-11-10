import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing key, kept outside the repo: keystore.properties (git-ignored) in the project root with
// storeFile, storePassword, keyAlias, keyPassword. Without it, release builds are unsigned.
val releaseSigning = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "org.fossistant.assistant"

    signingConfigs {
        if (releaseSigning.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }
    compileSdk = 35

    defaultConfig {
        applicationId = "org.fossistant.assistant"
        minSdk = 29 // Android 10+ for modern VoiceInteractionService, MediaCodec, & 64-bit mmap
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where APKs without bundled models (slim) download Gemma 4 E2B (MTP build). Override with
        // -PgemmaModelUrl=https://your.mirror/gemma-4-E2B-it.litertlm; the download is still checked against
        // the pinned size and SHA-256 in PrimaryLlmModel, so only the MTP build is accepted.
        val gemmaUrl = (project.findProperty("gemmaModelUrl") as String?)
            ?: "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        buildConfigField("String", "GEMMA_MODEL_URL", "\"$gemmaUrl\"")

        // Where voluntary crash reports are emailed (the user's email app opens; nothing is sent silently).
        // Set with -PcrashReportEmail=... or crashReportEmail=... in gradle.properties; empty = share sheet.
        val crashEmail = (project.findProperty("crashReportEmail") as String?).orEmpty()
        buildConfigField("String", "CRASH_REPORT_EMAIL", "\"$crashEmail\"")

        ndk {
            // Restrict to arm64-v8a (and x86_64 for emulator) for SIMD/DOTPROD/I8MM QAT & MTP efficiency
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // APKs with the same applicationId and signing key, so any one installs over another and keeps the data:
    // - full: Gemma 4 E2B (with its MTP drafter) and Whisper Small (app/src/full + app/src/voice assets).
    // - slim: code only (~140 MB). Installed over full, it only replaces the code.
    // On first launch the full APK unpacks its models into private storage, where they survive later
    // installs: install full once, then update with slim.
    flavorDimensions += "models"
    productFlavors {
        create("full") {
            dimension = "models"
            isDefault = true
            buildConfigField("boolean", "BUNDLES_MODELS", "true")
        }
        create("slim") {
            dimension = "models"
            versionNameSuffix = "-slim"
            buildConfigField("boolean", "BUNDLES_MODELS", "false")
        }
    }
    // Whisper (voice) ships in the model APK.
    sourceSets {
        getByName("full").assets.srcDir("src/voice/assets")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Not shrunk yet: tool calling uses Kotlin reflection and the native libraries call back into
            // Kotlin; R8 needs tested keep rules for both before it can be switched on.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-Xskip-metadata-version-check"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Keep neural network weight files uncompressed in APK/AAB for zero-copy mmap & fast streaming
        noCompress += listOf("litertlm", "onnx", "model", "tflite", "bin", "gguf")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The bundled models push the model APKs past 2 GB, which overflows AGP's incremental (in-place) APK update
// ("integer overflow" in packageFullDebug). Wiping the previous APK in a separate task before packaging makes
// Gradle see missing outputs and repackage from scratch instead.
listOf("Full").forEach { flavor ->
    listOf("Debug", "Release").forEach { buildType ->
        val wipe = tasks.register<Delete>("wipe$flavor${buildType}ApkBeforePackaging") {
            delete(
                layout.buildDirectory.dir("outputs/apk/${flavor.replaceFirstChar { it.lowercase() }}/${buildType.lowercase()}"),
                layout.buildDirectory.dir("intermediates/incremental/package$flavor$buildType")
            )
        }
        tasks.configureEach {
            if (name == "package$flavor$buildType") dependsOn(wipe)
        }
    }
}

dependencies {
    // Google LiteRT-LM 0.19.1 (Google Gemma 4 E2B on-device runtime)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")

    // Core Android & Coroutines
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Jetpack Compose Material 3
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // OkHttp & Jsoup for lightweight zero-API-key local Web Search & Page Extraction tools
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
