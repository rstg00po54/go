package com.badukai.app.ui.analysis

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import com.badukai.app.analysis.AnalysisMove
import com.badukai.app.ui.board.BoardCanvas
import com.badukai.app.ui.common.WinRateBar
import com.badukai.app.ui.common.WinRateChart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    onPickGame: () -> Unit,
    viewModel: AnalysisViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val series = remember(ui.winrateHistory) { viewModel.winrateSeries() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分析复盘 · ${ui.gameTitle}") },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp)
        ) {
            Box(Modifier.fillMaxWidth().padding(4.dp), contentAlignment = Alignment.Center) {
                BoardCanvas(
                    model = ui.board,
                    onPointClick = { /* 复盘模式不可落子 */ },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 胜率条
            val blackWin = ui.winrate?.let { if (ui.board.size > 0) it else null } ?: ui.winrate
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                WinRateBar(blackWinrate = blackWin ?: 50f)
            }

            // 胜率曲线
            if (series.size >= 2) {
                WinRateChart(points = series, modifier = Modifier.fillMaxWidth())
            }

            // 候选选点
            ui.analysis?.let { res ->
                Text("推荐选点", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                LazyColumn(Modifier.fillMaxWidth().height(120.dp)) {
                    items(res.moveInfos.take(6)) { mv -> MoveRow(mv) }
                }
            }

            ui.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.primary)
            }
            if (ui.batchProgress in 0.001f..0.999f) {
                LinearProgressIndicator(progress = { ui.batchProgress },
                    modifier = Modifier.fillMaxWidth().padding(8.dp))
            }

            // 导航滑块
            Slider(
                value = ui.currentIndex.toFloat(),
                onValueChange = { viewModel.goto(it.toInt()) },
                valueRange = 0f..ui.totalMoves.coerceAtLeast(1).toFloat(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
            Text("第 ${ui.currentIndex} / ${ui.totalMoves} 手",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp))

            Spacer(Modifier.height(4.dp))

            // 导航按钮
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = { viewModel.first() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.SkipPrevious, "开头")
                }
                IconButton(onClick = { viewModel.prev() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FastRewind, "上一步")
                }
                IconButton(onClick = { viewModel.next() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.SkipNext, "下一步")
                }
                IconButton(onClick = { viewModel.last() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FastForward, "末尾")
                }
            }
            Spacer(Modifier.height(6.dp))

            // 分析操作
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ui.analyzing) {
                    FilledTonalButton(onClick = { viewModel.stopAnalysis() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Stop, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text("停止")
                    }
                } else {
                    FilledTonalButton(onClick = { viewModel.analyzeCurrent() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text("分析当前")
                    }
                }
                OutlinedButton(onClick = { viewModel.analyzeWholeGame() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.GraphicEq, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text("整盘分析")
                }
                OutlinedButton(onClick = { viewModel.ensureEngine() }, modifier = Modifier.weight(1f)) {
                    Text("启动引擎")
                }
                OutlinedButton(onClick = onPickGame, modifier = Modifier.weight(1f)) {
                    Text("选谱")
                }
            }
        }
    }
}

@Composable
private fun MoveRow(mv: AnalysisMove) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(mv.move, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 8.dp))
        Text("胜率 ${mv.winrate.toInt()}%", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(8.dp))
        Text("访问 ${mv.visits}", style = MaterialTheme.typography.bodySmall)
        mv.scoreLead?.let {
            Spacer(Modifier.size(8.dp))
            Text("领先 ${"%.1f".format(it)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
