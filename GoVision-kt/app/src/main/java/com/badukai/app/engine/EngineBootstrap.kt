package com.badukai.app.engine

import android.content.Context
import java.io.File

/**
 * 引擎引导器：在应用首次启动（或版本升级）时，把打包在 assets/engine/ 下的
 * KataGo 可执行文件、LLVM libc++ 运行时库、默认 GTP 配置释放到应用私有目录，
 * 使其可被 [ProcessBuilder] 执行。
 *
 * assets 内约定：
 *  - assets/engine/katago             —— ARM64 KataGo 可执行文件（3.8MB，Eigen CPU 后端）
 *  - assets/engine/libc++_shared.so   —— NDK LLVM libc++ 运行时（1.8MB，和 katago 同目录）
 *  - assets/engine/default_gtp.cfg    —— 默认 GTP 配置（官方 gtp_example.cfg）
 *
 * 释放后路径（[filesDir]/engine/）：
 *  - katago            → 0755 可执行
 *  - libc++_shared.so  → 0644 不可执行，运行时通过 LD_LIBRARY_PATH 加载
 *  - default_gtp.cfg   → 0644
 *
 * 若 assets 中不存在（如本地 debug 包），则对应字段为空，交给 UI 引导用户去设置页。
 */
class EngineBootstrap(private val context: Context) {

    val engineDir: File = File(context.filesDir, "engine").apply { mkdirs() }

    internal val executableFile: File = File(engineDir, "katago")
    internal val libcxxFile: File = File(engineDir, "libc++_shared.so")
    internal val configFile: File = File(engineDir, "default_gtp.cfg")

    /** APK 是否打包了 KataGo 引擎文件 */
    fun isBundledBinaryAvailable(): Boolean =
        runCatching { context.assets.open(BINARY_ASSET).use { it.close() } }.isSuccess

    /** APK 是否打包了 libc++ 运行时库 */
    fun isBundledLibcxxAvailable(): Boolean =
        runCatching { context.assets.open(LIBCXX_ASSET).use { it.close() } }.isSuccess

    /**
     * 执行释放。每次版本升级都会强制覆盖。
     * @return ReleaseResult 描述释放结果
     */
    fun ensureExtracted(): ReleaseResult {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            pkgInfo.longVersionCode else pkgInfo.versionCode.toLong()
        val versionMarker = File(engineDir, ".v$versionCode")
        val needExtract = !versionMarker.exists()

        // ---- 释放 KataGo 二进制 ----
        var binaryReady = false
        var binaryWasBundled = false
        if (isBundledBinaryAvailable()) {
            binaryWasBundled = true
            runCatching {
                copyAsset(BINARY_ASSET, executableFile, overwrite = needExtract)
                executableFile.setReadable(true, false)
                executableFile.setWritable(true, false)
                executableFile.setExecutable(true, false) // rwxr-xr-x
            }
            binaryReady = executableFile.exists() && executableFile.canExecute()
        }

        // ---- 释放 libc++_shared.so（注意：设为 644，不可执行！）----
        var libcxxReady = false
        var libcxxWasBundled = false
        if (isBundledLibcxxAvailable()) {
            libcxxWasBundled = true
            runCatching {
                copyAsset(LIBCXX_ASSET, libcxxFile, overwrite = needExtract)
                libcxxFile.setReadable(true, false)
                libcxxFile.setWritable(true, false)
                libcxxFile.setExecutable(false, false) // rw-r--r--：明确关闭执行权限！
            }
            libcxxReady = libcxxFile.exists() && libcxxFile.canRead()
        }

        // ---- 释放 default_gtp.cfg ----
        val configOk = runCatching {
            copyAsset(CONFIG_ASSET, configFile, overwrite = needExtract)
            configFile.setReadable(true, false)
            configFile.setWritable(true, false)
        }.isSuccess && configFile.exists()

        if (needExtract) versionMarker.createNewFile()

        return ReleaseResult(
            binaryReady = binaryReady,
            binaryWasBundled = binaryWasBundled,
            libcxxReady = libcxxReady,
            libcxxWasBundled = libcxxWasBundled,
            configReady = configOk,
            engineDir = engineDir.absolutePath,
            executablePath = if (binaryReady) executableFile.absolutePath else "",
            libcxxPath = if (libcxxReady) libcxxFile.absolutePath else "",
            configPath = if (configOk) configFile.absolutePath else "",
            primaryAbi = primaryAbi(),
        )
    }

    /** 当前设备首选 ABI（arm64-v8a / armeabi-v7a / x86_64） */
    fun primaryAbi(): String? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP)
            android.os.Build.SUPPORTED_ABIS.firstOrNull()
        else android.os.Build.CPU_ABI

    private fun copyAsset(name: String, dest: File, overwrite: Boolean) {
        if (dest.exists() && !overwrite) return
        dest.parentFile?.mkdirs()
        context.assets.open(name).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    companion object {
        private const val BINARY_ASSET  = "engine/katago"
        private const val LIBCXX_ASSET  = "engine/libc++_shared.so"
        private const val CONFIG_ASSET  = "engine/default_gtp.cfg"
    }
}

/** 引擎资源释放结果 —— 包含 3 个文件的就绪状态和绝对路径 */
data class ReleaseResult(
    val binaryReady: Boolean,
    val binaryWasBundled: Boolean,
    val libcxxReady: Boolean,
    val libcxxWasBundled: Boolean,
    val configReady: Boolean,
    val engineDir: String,
    val executablePath: String,
    val libcxxPath: String,
    val configPath: String,
    val primaryAbi: String?,
)
