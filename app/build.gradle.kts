import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val secretsFile = rootProject.file("secrets.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.avera.jarvis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.avera.jarvis"
        minSdk = 27
        targetSdk = 27
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "OPENAI_API_KEY", "\"${secrets.getProperty("openai.api.key", "")}\"")
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${secrets.getProperty("openrouter.api.key", "")}\"")
        ndk { abiFilters.add("armeabi-v7a") } // device is 32-bit; keep only its native libs
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared") }
        }
    }

    // JNI glue for WebRTC AEC3 (the prebuilt .so lives in src/main/jniLibs)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1") // wake word (openWakeWord)
}
