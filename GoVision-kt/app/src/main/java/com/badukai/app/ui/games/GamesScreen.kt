package com.badukai.app.ui.games

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.badukai.app.data.GameEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    onOpenGame: (Long) -> Unit,
    viewModel: GamesViewModel = viewModel(),
) {
    val games by viewModel.games.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importFromUri(uri) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("棋谱") }, actions = {
                IconButton(onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) }) {
                    Icon(Icons.Default.FileOpen, "导入 SGF")
                }
            })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (games.isEmpty()) {
                Text(
                    "暂无棋谱。在对弈页面保存，或点击右上角导入 .sgf 文件。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp)
                )
            }
            LazyColumn(Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(games, key = { it.id }) { g -> GameCard(g, onOpen = { onOpenGame(g.id) }, onDelete = { viewModel.delete(g.id) }) }
            }
        }
    }
}

@Composable
private fun GameCard(g: GameEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(g.title.ifBlank { "未命名对局" }, style = MaterialTheme.typography.titleMedium)
                Text("${g.blackName.ifBlank { "黑" }} vs ${g.whiteName.ifBlank { "白" }} · ${g.boardSize}路 · 贴目 ${g.komi}",
                    style = MaterialTheme.typography.bodySmall)
                g.result?.let { Text("结果：$it", style = MaterialTheme.typography.bodySmall) }
                Text("更新 ${df.format(Date(g.updatedAt))}", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onOpen) { Icon(Icons.Default.PlayArrow, "复盘") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
        }
    }
}
