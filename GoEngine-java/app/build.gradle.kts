plugins {
    id("com.android.application")
}

android {
    namespace = "com.badukai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.badukai"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-java"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("lib/arm64-v8a/libc++_shared.so")
        }
    }

    aaptOptions {
        noCompress += listOf("tflite", "bin", "gz", "model", "so")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}
