package com.badukai.app.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.badukai.app.core.Stone
import com.badukai.app.ui.board.BoardCanvas
import com.badukai.app.ui.common.WinRateBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(
    onOpenSettings: () -> Unit,
    onOpenGames: () -> Unit,
    viewModel: PlayViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showNewGameDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("对弈") },
                actions = {
                    val statusText = if (ui.engineReady) "引擎就绪" else "未配引擎"
                    Text(statusText, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 8.dp))
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = onOpenGames) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "棋谱")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp)
        ) {
            // 回合指示
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (ui.turn == Stone.BLACK) Color(0xFF1A1A1A) else Color(0xFFFAFAFA))
                )
                Spacer(Modifier.width(8.dp))
                Text(if (ui.turn == Stone.BLACK) "${ui.blackName} 行棋" else "${ui.whiteName} 行棋",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (ui.aiThinking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("AI 思考中…", style = MaterialTheme.typography.labelSmall)
                }
            }

            // 棋盘
            Box(Modifier.fillMaxWidth().padding(4.dp), contentAlignment = Alignment.Center) {
                BoardCanvas(
                    model = ui.board,
                    onPointClick = { viewModel.onHumanPlay(it) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 胜率
            ui.winrate?.let { wr ->
                Card(colors = CardDefaults.cardColors(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    WinRateBar(blackWinrate = if (ui.turn == Stone.BLACK) wr else 100f - wr)
                }
            }

            // 消息
            ui.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary)
            }
            ui.scoreText?.let {
                Text("终局：$it", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 12.dp))
            }

            // 引擎错误卡片：引导用户前往设置页
            ui.engineError?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "⚠️ 引擎启动失败",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            FilledTonalButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("前往设置配置引擎")
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { viewModel.ensureEngine() }) {
                                Text("重试启动")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // 主操作按钮
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { viewModel.requestAiMove() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("AI落子")
                }
                OutlinedButton(onClick = { viewModel.requestAnalysis() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Tune, null); Spacer(Modifier.width(4.dp)); Text("分析")
                }
                OutlinedButton(onClick = { viewModel.toggleCoords() }, modifier = Modifier.weight(1f)) {
                    Text(if (ui.showCoords) "隐藏坐标" else "显示坐标")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.undo() }, modifier = Modifier.weight(1f)) { Text("悔棋") }
                OutlinedButton(onClick = { viewModel.pass() }, modifier = Modifier.weight(1f)) { Text("虚手") }
                OutlinedButton(onClick = { viewModel.resign() }, modifier = Modifier.weight(1f)) { Text("认输") }
                OutlinedButton(onClick = { showSaveDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showNewGameDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("新对局")
                }
                OutlinedButton(onClick = { viewModel.ensureEngine() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("启动引擎")
                }
            }
        }
    }

    if (showNewGameDialog) {
        NewGameDialog(onDismiss = { showNewGameDialog = false }, onConfirm = {
            viewModel.newGame(it); showNewGameDialog = false
        })
    }
    if (showSaveDialog) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存棋谱") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text("对局标题") }, singleLine = true)
            },
            confirmButton = { TextButton(onClick = { viewModel.save(title); showSaveDialog = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun NewGameDialog(onDismiss: () -> Unit, onConfirm: (NewGameParams) -> Unit) {
    var boardSize by remember { mutableStateOf(19) }
    var komi by remember { mutableStateOf("7.5") }
    var handicap by remember { mutableStateOf("0") }
    var humanColor by remember { mutableStateOf(Stone.BLACK) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新对局设置") },
        text = {
            Column {
                Text("棋盘大小", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(vertical = 4.dp)) {
                    listOf(9, 13, 19).forEach { s ->
                        FilledTonalButton(
                            onClick = { boardSize = s },
                            modifier = Modifier.padding(end = 4.dp)
                        ) { Text(if (s == boardSize) "[$s]" else s.toString()) }
                    }
                }
                OutlinedTextField(value = komi, onValueChange = { komi = it },
                    label = { Text("贴目") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                OutlinedTextField(value = handicap, onValueChange = { handicap = it },
                    label = { Text("让子") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                Text("玩家执", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(vertical = 4.dp)) {
                    FilledTonalButton(onClick = { humanColor = Stone.BLACK }, modifier = Modifier.padding(end = 4.dp)) {
                        Text(if (humanColor == Stone.BLACK) "[黑]" else "黑")
                    }
                    FilledTonalButton(onClick = { humanColor = Stone.WHITE }) {
                        Text(if (humanColor == Stone.WHITE) "[白]" else "白")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    NewGameParams(
                        boardSize = boardSize,
                        komi = komi.toFloatOrNull() ?: 7.5f,
                        handicap = handicap.toIntOrNull() ?: 0,
                        blackHuman = humanColor == Stone.BLACK,
                        whiteHuman = humanColor == Stone.WHITE,
                        blackName = if (humanColor == Stone.BLACK) "玩家" else "AI",
                        whiteName = if (humanColor == Stone.WHITE) "玩家" else "AI",
                    )
                )
            }) { Text("开始") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
