import java.io.File
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import java.nio.file.Files

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


    // KataGo is a PIE executable (not a JNI shared library). Gradle packages the
    // generated libkatago_exec.so as an extracted native library for ProcessBuilder.
    sourceSets {
        getByName("main") {
            jniLibs.setSrcDirs(listOf(layout.buildDirectory.dir("generated/katagoJniLibs").get().asFile))
        }
    }

    // Android 10+ does not allow executing binaries extracted under writable filesDir.
    // Package katago as libkatago_exec.so and extract native libs to nativeLibraryDir.
    // Only our arm64-v8a libraries are included; legacy SNPE/QNN/HTP libs stay out.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Keep the PIE executable intact; these binaries are intentionally not stripped.
            keepDebugSymbols += setOf("**/libkatago_exec.so", "**/libc++_shared.so")
        }
    }

    androidResources {
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


// KataGo is an Android ARM64 PIE executable, not a normal JNI .so. Build it
// with CMake/Ninja, then stage it in generated jniLibs before AGP merges libraries.
// Unlike externalNativeBuild, this handles CMake's add_executable(katago) output.
val skipKataGoNative = providers.gradleProperty("skipKataGoNative").orNull.equals("true", ignoreCase = true)
val katagoSourceDir = rootProject.file("native/KataGo/cpp")
val katagoWorkDir = File(System.getenv("KATAGO_WORKDIR") ?: "${System.getProperty("user.home")}/.cache/goengine_katago")
val katagoBuildDir = File(katagoWorkDir, "build_android_arm64_eigen")
val katagoExecutable = File(katagoBuildDir, "katago")
val katagoGeneratedJniDir = layout.buildDirectory.dir("generated/katagoJniLibs").get().asFile
val katagoPrebuiltDir = file("src/main/jniLibs/arm64-v8a")

val configureKataGoNative = tasks.register<Exec>("configureKataGoNative") {
    group = "build"
    description = "Configure KataGo C++ with Android NDK and CMake"
    onlyIf { !skipKataGoNative }
    doFirst {
        check(File(katagoSourceDir, "CMakeLists.txt").isFile) { "KataGo C++ source missing: $katagoSourceDir" }
        val props = Properties()
        val local = rootProject.file("local.properties")
        if (local.isFile) local.inputStream().use { props.load(it) }
        val sdkHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: props.getProperty("sdk.dir") ?: "${System.getProperty("user.home")}/Android/Sdk"
        val ndkHome = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT")
            ?: File(sdkHome, "ndk/27.2.12479018").absolutePath
        val toolchain = File(ndkHome, "build/cmake/android.toolchain.cmake")
        if (!toolchain.isFile) throw GradleException("Android NDK not found: $ndkHome (set ANDROID_NDK_HOME)")
        val explicitEigen = System.getenv("EIGEN3_CMAKE_DIR")
        val eigenCandidates = if (!explicitEigen.isNullOrBlank()) listOf(File(explicitEigen)) else listOf(
            File("/usr/lib/cmake/eigen3"), File("/usr/share/eigen3/cmake"),
            File("/usr/lib/x86_64-linux-gnu/cmake/eigen3"), File("/usr/local/lib/cmake/eigen3")
        )
        val eigenDir = eigenCandidates.firstOrNull { File(it, "Eigen3Config.cmake").isFile }
            ?: throw GradleException("Eigen3Config.cmake not found (install libeigen3-dev or set EIGEN3_CMAKE_DIR)")
        println("KataGo CMake: NDK=$ndkHome, Eigen3=${eigenDir.absolutePath}")
        (this as Exec).commandLine(
            "cmake", "-Wno-deprecated", "-S", katagoSourceDir.absolutePath, "-B", katagoBuildDir.absolutePath, "-G", "Ninja",
            "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}", "-DANDROID_ABI=arm64-v8a",
            "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
            "-DUSE_BACKEND=EIGEN", "-DEigen3_DIR=${eigenDir.absolutePath}",
            "-DBUILD_DISTRIBUTED=OFF", "-DNO_GIT_REVISION=ON", "-DUSE_AVX2=OFF",
            "-DUSE_TCMALLOC=OFF", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
            "-DCMAKE_EXE_LINKER_FLAGS=-pie",
            "-DCMAKE_CXX_FLAGS=-DLITTLE_ENDIAN=1234 -DBIG_ENDIAN=4321 -DBYTE_ORDER=1234"
        )
    }
}

val compileKataGoNative = tasks.register<Exec>("compileKataGoNative") {
    group = "build"
    description = "Compile KataGo ARM64 executable (incremental Ninja build)"
    dependsOn(configureKataGoNative)
    onlyIf { !skipKataGoNative }
    doFirst {
        val requested = System.getenv("KATAGO_JOBS")
        val jobs = if (requested.isNullOrBlank()) 8 else
            requested.toIntOrNull()?.takeIf { it > 0 } ?: throw GradleException("Invalid KATAGO_JOBS: $requested")
        println("KataGo C++ parallel jobs: $jobs")
        (this as Exec).commandLine("cmake", "--build", katagoBuildDir.absolutePath, "--parallel", jobs.toString())
    }
}

val stageKataGoNative = tasks.register("stageKataGoNative") {
    group = "build"
    description = "Stage KataGo executable for APK packaging"
    dependsOn(compileKataGoNative)
    val inputExe = if (skipKataGoNative) File(katagoPrebuiltDir, "libkatago_exec.so") else katagoExecutable
    inputs.file(inputExe)
    val sharedCpp = File(katagoPrebuiltDir, "libc++_shared.so")
    if (sharedCpp.isFile) inputs.file(sharedCpp)
    outputs.dir(katagoGeneratedJniDir)
    doLast {
        if (!inputExe.isFile) throw GradleException("KataGo native executable missing: $inputExe")
        if (!skipKataGoNative) {
            val inspector = ProcessBuilder("readelf", "-h", inputExe.absolutePath).redirectErrorStream(true)
            inspector.environment()["LC_ALL"] = "C"
            val process = inspector.start()
            val header = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            println(header.lineSequence().filter { it.trimStart().startsWith("Type:") ||
                it.trimStart().startsWith("Machine:") }.joinToString("\n"))
            if (exitCode != 0 || !Regex("(?m)^\\s*Machine:\\s*AArch64\\b").containsMatchIn(header) ||
                !Regex("(?m)^\\s*Type:\\s*DYN\\b").containsMatchIn(header)) {
                throw GradleException("Expected Android AArch64 PIE executable. Actual readelf -h:\n$header")
            }
        }
        val abiDir = File(katagoGeneratedJniDir, "arm64-v8a")
        abiDir.mkdirs()
        fun stageFile(source: File, name: String) {
            val target = File(abiDir, name)
            if (!target.isFile || Files.mismatch(source.toPath(), target.toPath()) != -1L) {
                source.copyTo(target, overwrite = true)
            }
        }
        stageFile(inputExe, "libkatago_exec.so")
        if (sharedCpp.isFile) stageFile(sharedCpp, "libc++_shared.so")
        println("KataGo native binary ready: ${File(abiDir, "libkatago_exec.so")}")
    }
}

// AGP reads generated jniLibs in merge<Variant>JniLibFolders, before merge<Variant>NativeLibs.
// Declare the producer dependency on both tasks to satisfy Gradle 8 input validation.
tasks.configureEach {
    if (name.startsWith("merge") && (name.endsWith("JniLibFolders") || name.endsWith("NativeLibs"))) {
        dependsOn(stageKataGoNative)
    }
}
