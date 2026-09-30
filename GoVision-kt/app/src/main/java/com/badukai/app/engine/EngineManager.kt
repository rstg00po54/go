package com.badukai.app.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.badukai.app.gtp.GtpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.engineDataStore: DataStore<Preferences> by preferencesDataStore(name = "engine_settings")

/**
 * 引擎配置的持久化管理与进程生命周期入口。
 *
 * 启动流程（安装即用）：
 *   1. APK assets/engine/ 下包含：katago + libc++_shared.so + default_gtp.cfg
 *   2. App 首次启动：[EngineBootstrap.ensureExtracted] 释放到 files/engine/
 *        katago → 0755 可执行
 *        libc++_shared.so → 0644 可读写（不可执行！）
 *        default_gtp.cfg → 0644
 *   3. [load] + withBundledDefaults：executablePath 回退到释放的 katago
 *   4. 用户只需选一次权重文件 → 对弈页「启动引擎」即可正常下棋
 *
 * 关键不变量：
 *   - executablePath 绝对不能指向 .so 文件（避免 Permission denied 错误）
 *   - 启动 katago 时必须注入 `LD_LIBRARY_PATH=<engineDir>`，否则 libc++ 加载失败
 */
class EngineManager(private val context: Context) {

    private val bootstrap = EngineBootstrap(context)

    private object Keys {
        val EXECUTABLE_PATH = stringPreferencesKey("executable_path")
        val MODEL_PATH = stringPreferencesKey("model_path")
        val CONFIG_PATH = stringPreferencesKey("config_path")
        val THREADS = intPreferencesKey("threads")
        val VISITS = intPreferencesKey("visits")
        val KOMI = floatPreferencesKey("komi")
    }

    @Volatile
    private var cached: EngineConfig = EngineConfig()

    private var lastRelease: ReleaseResult? = null

    val configFlow: Flow<EngineConfig> = context.engineDataStore.data
        .map { prefs -> prefs.toConfig().withBundledDefaults() }
        .catch { emit(EngineConfig().withBundledDefaults()) }

    suspend fun load(): EngineConfig {
        lastRelease = bootstrap.ensureExtracted()
        val cfg = configFlow.first()
        cached = cfg
        return cfg
    }

    suspend fun save(config: EngineConfig) {
        val valid = config.validated()
        context.engineDataStore.edit { prefs ->
            prefs[Keys.EXECUTABLE_PATH] = valid.executablePath
            prefs[Keys.MODEL_PATH] = valid.modelPath
            prefs[Keys.CONFIG_PATH] = valid.configPath
            prefs[Keys.THREADS] = valid.threads
            prefs[Keys.VISITS] = valid.visits
            prefs[Keys.KOMI] = valid.komi
        }
        cached = valid.withBundledDefaults()
    }

    /**
     * 把空的路径回退到内置释放路径。引擎文件路径必须经过 [validateExecutable] 过滤，
     * 永远不会把 .so 当可执行文件使用。
     */
    private fun EngineConfig.withBundledDefaults(): EngineConfig {
        val rel = lastRelease
        val rawExe = when {
            executablePath.isNotEmpty() -> executablePath
            rel != null && rel.binaryReady -> rel.executablePath
            else -> ""
        }
        val exe = validateExecutable(rawExe) ?: ""
        val cfg = when {
            configPath.isNotEmpty() -> configPath
            rel != null && rel.configReady -> rel.configPath
            else -> ""
        }
        return copy(executablePath = exe, configPath = cfg)
    }

    /** 校验可执行路径：拒绝 .so 共享库，拒绝目录；返回 null 表示不合法 */
    private fun validateExecutable(rawPath: String): String? {
        val p = rawPath.trim()
        if (p.isEmpty()) return null
        if (p.endsWith(".so", ignoreCase = true)) return null // 🚫 绝不能把 libc++ 当程序跑
        val f = File(p)
        if (!f.isFile) return null
        return p
    }

    /** 在 save 之前统一校验路径 */
    private fun EngineConfig.validated(): EngineConfig {
        val exe = validateExecutable(executablePath) ?: ""
        return copy(executablePath = exe)
    }

    fun buildLaunchArgs(config: EngineConfig): List<String> {
        val args = ArrayList<String>()
        args.add("gtp")
        if (config.modelPath.isNotEmpty()) {
            args.add("-model")
            args.add(config.modelPath)
        }
        if (config.configPath.isNotEmpty()) {
            args.add("-config")
            args.add(config.configPath)
        }
        args.add("-threads")
        args.add(config.threads.toString())
        return args
    }

    /**
     * 启动 KataGo GTP 进程。
     *
     * 关键修复：
     *   1. 强制过滤 .so：杜绝「把 libc++_shared.so 当可执行」的 Permission denied
     *   2. 注入环境变量 `LD_LIBRARY_PATH=<engineDir>` 让 katago 在同目录下找到 libc++_shared.so
     *   3. 三次校验空路径/文件存在/可执行权限
     */
    suspend fun start(config: EngineConfig, boardSize: Int = 19): GtpClient {
        val rawPath = config.executablePath.trim()
        val exePath = validateExecutable(rawPath)
            ?: throw IllegalStateException(
                "引擎路径不合法（不能是共享库 .so 文件）。\n" +
                    "当前路径：${rawPath.ifEmpty { "（空）" }}\n" +
                    "请在「设置」页重新选择真正的 KataGo 可执行文件（通常命名为 katago）。"
            )

        val exeFile = File(exePath)
        if (!exeFile.exists()) {
            throw IllegalStateException(
                "引擎文件不存在：$exePath\n请在「设置」页重新选择 KataGo 引擎可执行文件。"
            )
        }
        if (!exeFile.canExecute()) {
            throw IllegalStateException(
                "引擎文件没有执行权限：$exePath\n请在「设置」页重新选择 KataGo 引擎可执行文件，" +
                    "或确保存储上文件已被赋予可执行权限。"
            )
        }

        val engineDir = exeFile.parentFile ?: bootstrap.engineDir
        val workingDir = engineDir

        // ---- 关键：环境变量：LD_LIBRARY_PATH=引擎所在目录 ----
        // 如果用户自己放到自定义目录，也要让同目录的 libc++_shared.so 被加载到
        val env = buildMap<String, String> {
            put("LD_LIBRARY_PATH", engineDir.absolutePath)
            lastRelease?.let { rel ->
                if (rel.libcxxReady && rel.engineDir.isNotEmpty()
                    && rel.engineDir != engineDir.absolutePath) {
                    // 如果 katago 在非引擎释放目录，且已释放了一份 libc++ 到 engineDir，
                    // 把释放目录也加到 LD_LIBRARY_PATH
                    val prev = get("LD_LIBRARY_PATH").orEmpty()
                    put("LD_LIBRARY_PATH",
                        if (prev.isEmpty()) rel.engineDir else "$prev:${rel.engineDir}")
                }
            }
        }

        val client = GtpClient(
            executablePath = exePath,
            workingDir = workingDir,
            args = buildLaunchArgs(config),
            env = env,
        )
        client.boardsize(boardSize)
        client.komi(config.komi)
        client.clear_board()
        cached = config
        return client
    }

    fun isEngineAvailable(): Boolean {
        val p = validateExecutable(cached.executablePath)
        if (p != null && File(p).exists() && File(p).canExecute()) return true
        val rel = lastRelease
        if (rel != null && rel.binaryReady) return true
        return false
    }

    fun isReady(): Boolean = isEngineAvailable() && cached.modelPath.isNotEmpty()

    fun isBundled(): Boolean = bootstrap.isBundledBinaryAvailable()

    fun isLibcxxBundled(): Boolean = bootstrap.isBundledLibcxxAvailable()

    fun getLastRelease(): ReleaseResult? = lastRelease

    suspend fun pickAndSaveModel(uri: android.net.Uri): String {
        val dir = bootstrap.engineDir
        val displayName = queryDisplayName(uri) ?: "model.bin.gz"
        val dest = File(dir, displayName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw java.io.IOException("无法读取所选文件")
        val current = cached
        save(current.copy(modelPath = dest.absolutePath))
        return dest.absolutePath
    }

    suspend fun pickAndSaveExecutable(uri: android.net.Uri): String {
        val dir = bootstrap.engineDir
        val displayName = queryDisplayName(uri) ?: "katago"
        // 防御：如果用户选的是 .so 文件，直接拒绝
        if (displayName.endsWith(".so", ignoreCase = true)) {
            throw IllegalArgumentException("您选择了共享库文件（.so），请选择真正的 KataGo 可执行文件。")
        }
        val dest = File(dir, displayName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw java.io.IOException("无法读取所选文件")
        dest.setExecutable(true, false)
        dest.setReadable(true, false)
        val current = cached
        save(current.copy(executablePath = dest.absolutePath))
        return dest.absolutePath
    }

    suspend fun pickAndSaveConfig(uri: android.net.Uri): String {
        val dir = bootstrap.engineDir
        val displayName = queryDisplayName(uri) ?: "config.cfg"
        val dest = File(dir, displayName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw java.io.IOException("无法读取所选文件")
        val current = cached
        save(current.copy(configPath = dest.absolutePath))
        return dest.absolutePath
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        return cursor.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) it.getString(idx) else null
        }
    }

    private fun Preferences.toConfig(): EngineConfig = EngineConfig(
        executablePath = (this[Keys.EXECUTABLE_PATH] ?: "").let { validateExecutable(it) ?: "" },
        modelPath = this[Keys.MODEL_PATH] ?: "",
        configPath = this[Keys.CONFIG_PATH] ?: "",
        threads = this[Keys.THREADS] ?: 4,
        visits = this[Keys.VISITS] ?: 800,
        komi = this[Keys.KOMI] ?: 7.5f
    )
}
