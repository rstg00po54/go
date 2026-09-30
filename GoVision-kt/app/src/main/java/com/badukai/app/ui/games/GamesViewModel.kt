package com.badukai.app.ui.games

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badukai.app.data.GameDatabase
import com.badukai.app.data.GameEntity
import com.badukai.app.data.GameRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 棋谱列表页面 ViewModel */
class GamesViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = GameRepository(GameDatabase.get(app).gameDao())

    val games: StateFlow<List<GameEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 从内容 URI 导入 SGF 文件 */
    fun importFromUri(uri: Uri, defaultTitle: String = "导入棋谱") {
        viewModelScope.launch {
            val text = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: return@launch
            val title = defaultTitle.ifBlank { uri.lastPathSegment ?: "导入棋谱" }
            repository.importFromSgf(text, title)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}
