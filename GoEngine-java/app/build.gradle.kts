import java.io.File
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import java.nio.file.Files

plugins {
    id("com.android.application")
}

val enableKataGoProbe = providers.gradleProperty("enableKataGoProbe")
    .map { it.equals("true", ignoreCase = true) }.getOrElse(false)
// Preserve already-built JNI backends on ordinary assembleDebug. Otherwise a
// simple Java/UI rebuild silently deletes libkatago_gpu.so from the APK and
// disables the CPU/GPU selector. Explicit -PenableKataGoGpuJni=false (or
// -PenableKataGoJniCore=false) still opts out for a CPU-only test build.
val cachedCpuJni = rootProject.file("build/katago_android_arm64_eigenjni/libkatago.so")
val cachedGpuJni = rootProject.file("build/katago_android_arm64_opencljni/libkatago_gpu.so")
val enableKataGoJniCore = providers.gradleProperty("enableKataGoJniCore")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(cachedCpuJni.isFile && cachedCpuJni.length() > 0L)
val enableKataGoGpuJni = providers.gradleProperty("enableKataGoGpuJni")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(cachedGpuJni.isFile && cachedGpuJni.length() > 0L)

android {
    namespace = "com.badukai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.badukai.java"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-java"
        manifestPlaceholders["gpuJniSmokeEnabled"] = enableKataGoGpuJni.toString()
        if (enableKataGoProbe) ndk { abiFilters += "arm64-v8a" }
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


    // Opt-in JNI/OpenCL visibility check. Normal APK builds keep their old
    // ProcessBuilder baseline and do not configure an extra CMake project.
    if (enableKataGoProbe) {
        externalNativeBuild {
            cmake { path = file("src/main/cpp/CMakeLists.txt") }
        }
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
            keepDebugSymbols += setOf("**/libkatago_exec.so", "**/libkatago_gpu.so", "**/libc++_shared.so")
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
val katagoJniCoreFile = rootProject.file("build/katago_android_arm64_eigenjni/libkatago.so")
val katagoGpuJniFile = rootProject.file("build/katago_android_arm64_opencljni/libkatago_gpu.so")

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
            "-DCMAKE_EXE_LINKER_FLAGS=-pie -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384",
            "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384",
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
        // A cached 4 KB PIE can remain "up to date" to Ninja even when current
        // CMake linker flags request 16 KB pages. Invalidate only the old linked
        // executable; object files and CPU/GPU JNI caches are kept unchanged.
        val invalidator = rootProject.file("tools/invalidate_unaligned_pie.py")
        val repair = ProcessBuilder("python3", invalidator.absolutePath, katagoExecutable.absolutePath)
            .redirectErrorStream(true).start()
        val repairOutput = repair.inputStream.bufferedReader().use { it.readText() }
        if (repair.waitFor() != 0) throw GradleException("KataGo PIE cache check failed: $repairOutput")
        println(repairOutput.trim())
        (this as Exec).commandLine("cmake", "--build", katagoBuildDir.absolutePath, "--parallel", jobs.toString())
    }
}

val stageKataGoNative = tasks.register("stageKataGoNative") {
    group = "build"
    description = "Stage 16 KB-aligned KataGo executable and JNI libraries"
    dependsOn(compileKataGoNative)
    // --apk-only must not blindly stage the legacy 4 KB-aligned PIE from jniLibs.
    // Reuse an existing 16 KB-compatible PIE if present. The independent
    // win-rate engine still uses ProcessBuilder, so PIE is required.
    val pieCandidates = if (skipKataGoNative) listOf(
        File(katagoPrebuiltDir, "libkatago_exec.so"),
        katagoExecutable,
        rootProject.file("build/katago_android_arm64_eigen/libkatago_exec.so")
    ) else listOf(katagoExecutable)
    pieCandidates.filter { it.isFile }.forEach { inputs.file(it) }
    val sharedCpp = File(katagoPrebuiltDir, "libc++_shared.so")
    if (sharedCpp.isFile) inputs.file(sharedCpp)
    inputs.property("enableKataGoJniCore", enableKataGoJniCore)
    inputs.property("enableKataGoGpuJni", enableKataGoGpuJni)
    if (enableKataGoJniCore) inputs.file(katagoJniCoreFile)
    if (enableKataGoGpuJni) inputs.file(katagoGpuJniFile)
    outputs.dir(katagoGeneratedJniDir)
    doLast {
        fun readElf(file: File, option: String): String {
            val process = ProcessBuilder("readelf", "-W", option, file.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() != 0) throw GradleException("readelf $option failed on ${file.name}: $output")
            return output
        }
        fun elfLoadAlignments(file: File): List<Long?> {
            return readElf(file, "-l").lineSequence()
                .filter { it.trimStart().startsWith("LOAD ") }
                .map { it.trim().split(Regex("\\s+")).last().removePrefix("0x").toLongOrNull(16) }
                .toList()
        }
        val pieInspector = rootProject.file("tools/check_katago_pie.py")
        fun isAlignedPie(file: File): Boolean {
            if (!file.isFile) return false
            val process = ProcessBuilder("python3", pieInspector.absolutePath, file.absolutePath)
                .redirectErrorStream(true).start()
            val verdict = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val accepted = process.waitFor() == 0
            println(verdict)
            return accepted
        }
        val executable = pieCandidates.firstOrNull { isAlignedPie(it) }
        if (executable == null) {
            throw GradleException("No 16 KB-aligned KataGo PIE executable found. The independent " +
                "win-rate engine requires libkatago_exec.so even when the playing engine uses JNI. " +
                "Rebuild the PIE with bash tools/build_katago_from_source.sh eigen, " +
                "then retry bash build.sh --apk-only.")
        }
        val abiDir = File(katagoGeneratedJniDir, "arm64-v8a")
        abiDir.mkdirs()
        fun stageFile(source: File, name: String) {
            val target = File(abiDir, name)
            if (!target.isFile || Files.mismatch(source.toPath(), target.toPath()) != -1L)
                source.copyTo(target, overwrite = true)
        }
        stageFile(executable, "libkatago_exec.so")
        val stagedBinaries = mutableListOf(File(abiDir, "libkatago_exec.so"))
        println("Using 16 KB-aligned KataGo PIE: ${executable.absolutePath}")
        if (enableKataGoJniCore) {
            if (!katagoJniCoreFile.isFile) throw GradleException(
                "JNI core missing: $katagoJniCoreFile. Run bash tools/build_katago_from_source.sh eigenjni first.")
            stageFile(katagoJniCoreFile, "libkatago.so")
            stagedBinaries.add(File(abiDir, "libkatago.so"))
        } else {
            File(abiDir, "libkatago.so").delete()
        }
        if (enableKataGoGpuJni) {
            if (!katagoGpuJniFile.isFile) throw GradleException(
                "GPU JNI library missing: $katagoGpuJniFile. Run bash tools/build_katago_from_source.sh opencljni first.")
            stageFile(katagoGpuJniFile, "libkatago_gpu.so")
            stagedBinaries.add(File(abiDir, "libkatago_gpu.so"))
        } else {
            File(abiDir, "libkatago_gpu.so").delete()
        }
        val needsCppShared = stagedBinaries.any { file ->
            readElf(file, "-d").lineSequence().any {
                it.contains("(NEEDED)") && it.contains("[libc++_shared.so]")
            }
        }
        val stagedCpp = File(abiDir, "libc++_shared.so")
        if (needsCppShared) {
            if (!sharedCpp.isFile) throw GradleException(
                "KataGo requires libc++_shared.so but it is missing. Rebuild with ANDROID_STL=c++_static " +
                "or supply a 16 KB-aligned runtime from NDK r28+.")
            stageFile(sharedCpp, "libc++_shared.so")
            stagedBinaries.add(stagedCpp)
        } else {
            stagedCpp.delete()
        }
        // Reuse the same ARM64 + PIE + 16 KB validator as build.sh.
        for (file in stagedBinaries) {
            if (!isAlignedPie(file))
                throw GradleException("Invalid or non-16 KB-aligned Android ARM64 native binary: ${file.absolutePath}")
        }
        println("KataGo native libraries ready in: ${abiDir.absolutePath}")
    }
}

// AGP reads generated jniLibs in merge<Variant>JniLibFolders, before merge<Variant>NativeLibs.
// Declare the producer dependency on both tasks to satisfy Gradle 8 input validation.
tasks.configureEach {
    if (name.startsWith("merge") && (name.endsWith("JniLibFolders") || name.endsWith("NativeLibs"))) {
        dependsOn(stageKataGoNative)
    }
}
