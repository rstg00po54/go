package com.badukai.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badukai.app.engine.EngineConfig
import com.badukai.app.engine.EngineManager
import com.badukai.app.engine.ReleaseResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val executablePath: String = "",
    val modelPath: String = "",
    val configPath: String = "",
    val threads: Int = 4,
    val visits: Int = 800,
    val komi: Float = 7.5f,
    val message: String? = null,
    val engineAvailable: Boolean = false,
    val libcxxAvailable: Boolean = false,
    val bundled: Boolean = false,
    val ready: Boolean = false,
    val releaseResult: ReleaseResult? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val engineManager = EngineManager(app)

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val cfg = engineManager.load()
            val rel = engineManager.getLastRelease()
            _ui.value = SettingsUiState(
                executablePath = cfg.executablePath,
                modelPath = cfg.modelPath,
                configPath = cfg.configPath,
                threads = cfg.threads,
                visits = cfg.visits,
                komi = cfg.komi,
                engineAvailable = engineManager.isEngineAvailable(),
                libcxxAvailable = rel?.libcxxReady == true,
                bundled = engineManager.isBundled(),
                ready = engineManager.isReady(),
                releaseResult = rel,
            )
        }
    }

    fun updateExecutable(v: String) = _ui.update { it.copy(executablePath = v) }
    fun updateModel(v: String) = _ui.update { it.copy(modelPath = v) }
    fun updateConfig(v: String) = _ui.update { it.copy(configPath = v) }
    fun updateThreads(v: Int) = _ui.update { it.copy(threads = v.coerceIn(1, 64)) }
    fun updateVisits(v: Int) = _ui.update { it.copy(visits = v.coerceIn(1, 100000)) }
    fun updateKomi(v: Float) = _ui.update { it.copy(komi = v) }

    /**
     * 通过 SAF 选取文件：引擎/权重/配置
     * 调用 EngineManager 的 pickAndSave* 方法，保证引擎不选 .so 文件、
     * 自动赋执行权限、自动复制到 files/engine/ 目录。
     */
    fun pickFile(uri: Uri, kind: PickKind) {
        viewModelScope.launch {
            try {
                val path = when (kind) {
                    PickKind.EXECUTABLE -> engineManager.pickAndSaveExecutable(uri)
                    PickKind.MODEL -> engineManager.pickAndSaveModel(uri)
                    PickKind.CONFIG -> engineManager.pickAndSaveConfig(uri)
                }
                when (kind) {
                    PickKind.EXECUTABLE -> _ui.update { it.copy(executablePath = path) }
                    PickKind.MODEL -> _ui.update { it.copy(modelPath = path) }
                    PickKind.CONFIG -> _ui.update { it.copy(configPath = path) }
                }
                val rel = engineManager.getLastRelease()
                _ui.update { it.copy(
                    message = "✅ 已复制：${path.substringAfterLast('/')}",
                    engineAvailable = engineManager.isEngineAvailable(),
                    libcxxAvailable = rel?.libcxxReady == true,
                    ready = engineManager.isReady(),
                    releaseResult = rel,
                ) }
            } catch (e: Exception) {
                val msg = when (e.message) {
                    in setOf(
                        "You have selected a shared library (.so) file. Please select the actual KataGo executable.",
                        "您选择了共享库文件（.so），请选择真正的 KataGo 可执行文件。"
                    ) -> "❌ 您选择了共享库文件（.so），请选择真正的 KataGo 可执行文件（通常名为 katago）。"
                    else -> "❌ 复制失败：${e.message}"
                }
                _ui.update { it.copy(message = msg) }
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            val cfg = EngineConfig(
                executablePath = _ui.value.executablePath,
                modelPath = _ui.value.modelPath,
                configPath = _ui.value.configPath,
                threads = _ui.value.threads,
                visits = _ui.value.visits,
                komi = _ui.value.komi,
            )
            engineManager.save(cfg)
            val rel = engineManager.getLastRelease()
            _ui.update { it.copy(
                message = "✅ 设置已保存",
                engineAvailable = engineManager.isEngineAvailable(),
                libcxxAvailable = rel?.libcxxReady == true,
                ready = engineManager.isReady(),
                releaseResult = rel,
            ) }
        }
    }
}

enum class PickKind { EXECUTABLE, MODEL, CONFIG }
