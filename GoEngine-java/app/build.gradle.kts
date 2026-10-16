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
val cachedNpuJni = rootProject.file("build/katago_android_arm64_rknnjni/libkatago_npu.so")
val rknnSdkRuntime = rootProject.file("build/rknn_sdk/librknnrt.so")
val rknnModelAsset = file("src/main/assets/engine/katago_10b_19x19.rknn")
val cachedMtkJni = rootProject.file("build/katago_android_arm64_mediatekjni/libkatago_mtk.so")
// Opt-in only: no normal APK, CPU, GPU, or RKNN packaging changes until GTP is validated.
val enableKataGoMtkJni = providers.gradleProperty("enableKataGoMtkJni")
    .map { it.equals("true", ignoreCase = true) }.getOrElse(false)
val enableKataGoNpuJni = providers.gradleProperty("enableKataGoNpuJni")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(cachedNpuJni.isFile && rknnSdkRuntime.isFile && rknnModelAsset.isFile)
// Debug-only LiteRT graph probe: skip the unrelated KataGo PIE requirement.
// The probe is launched by ADB intent and is NOT a full playable game APK.
val vivoProbeApk = providers.gradleProperty("vivoProbeApk")
    .map { it.equals("true", ignoreCase = true) }.getOrElse(false)
// Normal, playable Vivo APK: include KataGo PIE + CPU/GPU JNI alongside the
// experimental MediaTek LiteRT test models. Unlike vivoProbeApk, this does
// NOT omit the native game engine. RK3588 default builds remain unchanged.
val bundleVivoLiteRtModels = providers.gradleProperty("bundleVivoLiteRtModels")
    .map { it.equals("true", ignoreCase = true) }.getOrElse(false)
val prepareVivoModels = vivoProbeApk || bundleVivoLiteRtModels
val allow4kRknnRuntime = providers.gradleProperty("allow4kRknnRuntime")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(System.getenv("ALLOW_4K_RKNN_RUNTIME") == "1")

android {
    namespace = "com.badukai"
    // LiteRT 2.3.0 dependencies require API 35 at compile time.
    compileSdk = 35

    defaultConfig {
        // Side-by-side NPU development build; preserve the installed com.badukai.java app.
        // Keep namespace=com.badukai so Java/JNI class names do not change.
        applicationId = "com.badukai.java.nputest"
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

    // Package the checked-in green home artwork from docs automatically.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/homeAssets"))
    // RK3588 NPU builds contain the validated original Human b18 9x9 model.
    // The model comes from a pinned Release, never from git or a test flag.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/rk3588HumanAssets"))

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
        noCompress += listOf("bin", "gz", "model", "so", "tflite")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.ai.edge.litert:litert:2.3.0")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-mediatek:2.3.0")
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
    inputs.property("enableKataGoNpuJni", enableKataGoNpuJni)
    inputs.property("enableKataGoMtkJni", enableKataGoMtkJni)
    if (enableKataGoMtkJni) inputs.file(cachedMtkJni)
    inputs.property("allow4kRknnRuntime", allow4kRknnRuntime)
    if (enableKataGoJniCore) inputs.file(katagoJniCoreFile)
    if (enableKataGoGpuJni) inputs.file(katagoGpuJniFile)
    if (enableKataGoNpuJni) {
        inputs.file(cachedNpuJni)
        inputs.file(rknnSdkRuntime)
        inputs.file(rknnModelAsset)
    }
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
        fun isAlignedPie(file: File, pageSize: Int = 16384): Boolean {
            if (!file.isFile) return false
            val process = ProcessBuilder("python3", pieInspector.absolutePath, "--page-size", pageSize.toString(), file.absolutePath)
                .redirectErrorStream(true).start()
            val verdict = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val accepted = process.waitFor() == 0
            println(verdict)
            return accepted
        }
        val executable = if (vivoProbeApk) null else pieCandidates.firstOrNull { isAlignedPie(it) }
        if (!vivoProbeApk && executable == null) {
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
        val stagedBinaries = mutableListOf<File>()
        if (executable != null) {
            stageFile(executable, "libkatago_exec.so")
            stagedBinaries.add(File(abiDir, "libkatago_exec.so"))
            println("Using 16 KB-aligned KataGo PIE: ${executable.absolutePath}")
        } else {
            File(abiDir, "libkatago_exec.so").delete()
            println("Vivo LiteRT probe APK: omitting unrelated KataGo PIE (no real game support)")
        }
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
        if (enableKataGoNpuJni) {
            if (!cachedNpuJni.isFile || !rknnSdkRuntime.isFile || !rknnModelAsset.isFile)
                throw GradleException("NPU artifacts missing: run bash tools/prepare_rknn.sh and bash tools/build_katago_from_source.sh rknnjni")
            stageFile(cachedNpuJni, "libkatago_npu.so")
            stageFile(rknnSdkRuntime, "librknnrt.so")
            stagedBinaries.add(File(abiDir, "libkatago_npu.so"))
            stagedBinaries.add(File(abiDir, "librknnrt.so"))
        } else {
            File(abiDir, "libkatago_npu.so").delete()
            File(abiDir, "librknnrt.so").delete()
        }
        if (enableKataGoMtkJni) {
            if (!cachedMtkJni.isFile) throw GradleException(
                "MediaTek GTP JNI missing: run bash tools/build_katago_from_source.sh mediatekjni")
            if (enableKataGoNpuJni) throw GradleException(
                "MediaTek and RKNN libraries must not be packaged together")
            stageFile(cachedMtkJni, "libkatago_mtk.so")
            stagedBinaries.add(File(abiDir, "libkatago_mtk.so"))
        } else {
            File(abiDir, "libkatago_mtk.so").delete()
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
        // Our own binaries MUST remain 16 KB aligned. A known Rockchip v2.3.2
        // runtime is shipped only for opt-in RK3588 Android with 4 KB pages.
        // This exception does NOT make an APK usable on a 16 KB page-size device.
        for (file in stagedBinaries) {
            val vendor4kException = allow4kRknnRuntime && enableKataGoNpuJni && file.name == "librknnrt.so"
            if (!isAlignedPie(file, if (vendor4kException) 4096 else 16384))
                throw GradleException("Invalid or insufficiently aligned Android ARM64 native binary: ${file.absolutePath}")
            if (vendor4kException)
                logger.warn("RK3588 4 KB ONLY: packaged Rockchip librknnrt.so prevents 16 KB page-size compatibility")
        }
        println("KataGo native libraries ready in: ${abiDir.absolutePath}")
    }
}

// 9x9 is hardware-verified. 13/19 are separately released and SHA-pinned;
// they keep four native CPU/NPU safety parity checks until board verification.
val requireHumanRknnMultisize = providers.gradleProperty("requireHumanRknnMultisize")
    .map { it.equals("true", ignoreCase = true) }.getOrElse(false)
val stageRk3588HumanRknn = tasks.register<Exec>("stageRk3588HumanRknn") {
    group = "build"
    description = "SHA256-verify and stage Human b18 9/13/19 RKNN for RK3588 NPU APK"
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/stage_rk3588_human_rknn.py",
        if (enableKataGoNpuJni) "--enabled" else "--disabled")
    if (requireHumanRknnMultisize && enableKataGoNpuJni)
        args("--require-multisize")
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(stageRk3588HumanRknn)
}

// Probe-only vivo builds must never silently omit the six KataGo LiteRT
// model files. Download/verify before AGP snapshots its main source-set assets.
// The script uses SHA256-pinned cached files and fails the build on network/
// integrity errors. This task is NOT run for ordinary RK3588 builds.
val prepareVivoLiteRtModels = tasks.register<Exec>("prepareVivoLiteRtModels") {
    group = "build"
    description = "Fetch and SHA256-check 9/13/19 LiteRT model assets before packaging"
    onlyIf { prepareVivoModels }
    workingDir(rootProject.projectDir)
    commandLine("bash", "tools/prepare_vivo_litert.sh")
}

val verifyVivoLiteRtApk = tasks.register<Exec>("verifyVivoLiteRtApk") {
    group = "verification"
    description = "Reject APKs missing any of the six pinned Vivo LiteRT assets"
    onlyIf { prepareVivoModels }
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/verify_vivo_litert_apk.py",
        "app/build/outputs/apk/debug/app-debug.apk")
}

tasks.configureEach {
    if (prepareVivoModels && name.startsWith("merge") && name.endsWith("Assets"))
        dependsOn(prepareVivoLiteRtModels)
    if (prepareVivoModels && name == "assembleDebug")
        finalizedBy(verifyVivoLiteRtApk)
}

// AGP reads generated jniLibs in merge<Variant>JniLibFolders, before merge<Variant>NativeLibs.
// Declare the producer dependency on both tasks to satisfy Gradle 8 input validation.
tasks.configureEach {
    if (name.startsWith("merge") && (name.endsWith("JniLibFolders") || name.endsWith("NativeLibs"))) {
        dependsOn(stageKataGoNative)
    }
}

// Independently packaged home illustrations and tactile button artwork.
val stageGreenHomeArtwork = tasks.register<Copy>("stageGreenHomeArtwork") {
    into(layout.buildDirectory.dir("generated/homeAssets/home"))
    doFirst { check(rootProject.file("docs/home/月门竹影下的围棋雅室.png").isFile) { "Missing home artwork under docs/home" } }
    from(rootProject.file("docs/home/月门竹影下的围棋雅室.png")) { rename { "background.png" } }
    from(rootProject.file("docs/home/人机对弈翡翠金饰按钮.png")) { rename { "play.png" } }
    from(rootProject.file("docs/home/玉韵棋谱复盘按钮.png")) { rename { "record.png" } }
    from(rootProject.file("docs/home/玉璧雅韵_死活训练.png")) { rename { "training.png" } }
    from(rootProject.file("docs/home/翡翠鎏金竹韵设置图标.png")) { rename { "settings.png" } }
    from(rootProject.file("docs/navbar")) { include("*.png"); into("navbar") }
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(stageGreenHomeArtwork)
}
