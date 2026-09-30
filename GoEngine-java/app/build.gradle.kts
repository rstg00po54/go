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
