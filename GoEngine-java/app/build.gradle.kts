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

    // RK3588 版本不再打包旧的 SNPE/QNN/HTP JNI 库。
    // libc++_shared.so 作为 assets/engine 下的普通文件释放到 filesDir/engine。
    packaging {
        jniLibs {
            excludes += setOf("**/*.so")
        }
    }

    aaptOptions {
        noCompress += listOf("bin", "gz", "model", "so")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}
