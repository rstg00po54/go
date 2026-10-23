plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.mtkprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.mtkprobe"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:2.3.0")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-mediatek:2.3.0")
}
