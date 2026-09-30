plugins { id("com.android.application") }

android {
    namespace = "com.badukai.java"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.badukai.java"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-java"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { viewBinding = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity:1.9.2")
    implementation("androidx.fragment:fragment:1.8.4")
}
