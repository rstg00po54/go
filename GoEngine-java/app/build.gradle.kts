plugins {
    id("com.android.application")
}

android {
    namespace = "com.badukai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.badukai.java"
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

    // Android 10+ does not allow executing binaries extracted under writable filesDir.
    // Package katago as libkatago_exec.so and extract native libs to nativeLibraryDir.
    // Only our arm64-v8a libraries are included; legacy SNPE/QNN/HTP libs stay out.
    packaging {
        jniLibs {
            useLegacyPackaging = true
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
