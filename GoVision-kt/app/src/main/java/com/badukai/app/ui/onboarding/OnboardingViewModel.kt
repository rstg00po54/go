package com.badukai.app.ui.onboarding

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badukai.app.engine.EngineManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 首次启动引导状态机。
 *
 * 三种状态：
 * 1. 需要引擎 + 权重（无引擎 + 无权重）→ 引导去设置
 * 2. 需要权重（有引擎 + 无权重）→ 弹出权重选择器
 * 3. 全部就绪 → 不显示引导
 */
data class OnboardingState(
    val visible: Boolean = false,
    val mode: OnboardingMode = OnboardingMode.PICK_WEIGHT,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val needsEngineSetup: Boolean get() = mode == OnboardingMode.SETUP_ENGINE
    val needsWeightPick: Boolean get() = mode == OnboardingMode.PICK_WEIGHT
}

enum class OnboardingMode {
    /** 需要用户在设置页配置引擎（无可用引擎） */
    SETUP_ENGINE,
    /** 引擎就绪但需要选择权重文件 */
    PICK_WEIGHT,
}

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val engineManager = EngineManager(app)

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // load() 会触发引擎资源释放
            engineManager.load()
            engineManager.configFlow.collect { cfg ->
                val engineOk = engineManager.isEngineAvailable()
                val weightOk = cfg.modelPath.isNotEmpty()
                _state.update {
                    when {
                        !engineOk -> it.copy(
                            visible = true,
                            mode = OnboardingMode.SETUP_ENGINE,
                            saving = false,
                        )
                        !weightOk -> it.copy(
                            visible = true,
                            mode = OnboardingMode.PICK_WEIGHT,
                            saving = false,
                        )
                        else -> it.copy(visible = false)
                    }
                }
            }
        }
    }

    fun onWeightPicked(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(saving = true, message = "正在复制权重文件…") }
            try {
                engineManager.pickAndSaveModel(uri)
                _state.update { it.copy(visible = false, saving = false, message = null) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, message = "失败：${e.message}") }
            }
        }
    }

    fun dismiss() = _state.update { it.copy(visible = false) }
}
