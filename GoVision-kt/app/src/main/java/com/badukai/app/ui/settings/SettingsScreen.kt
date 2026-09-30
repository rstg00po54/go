package com.badukai.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pickExecutable = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) viewModel.pickFile(it, PickKind.EXECUTABLE)
    }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) viewModel.pickFile(it, PickKind.MODEL)
    }
    val pickConfig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) viewModel.pickFile(it, PickKind.CONFIG)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("KataGo 引擎配置", style = MaterialTheme.typography.titleMedium)

            PathField(
                label = "引擎可执行文件路径",
                value = ui.executablePath,
                onValueChange = { viewModel.updateExecutable(it) },
                onPick = { pickExecutable.launch(arrayOf("*/*")) }
            )
            PathField(
                label = "权重文件路径 (.bin.gz / .txt)",
                value = ui.modelPath,
                onValueChange = { viewModel.updateModel(it) },
                onPick = { pickModel.launch(arrayOf("*/*")) }
            )
            PathField(
                label = "GTP 配置文件路径 (.cfg)",
                value = ui.configPath,
                onValueChange = { viewModel.updateConfig(it) },
                onPick = { pickConfig.launch(arrayOf("*/*")) }
            )

            Text("线程数：${ui.threads}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = ui.threads.toFloat(),
                onValueChange = { viewModel.updateThreads(it.toInt()) },
                valueRange = 1f..16f
            )

            Text("分析访问数（visits）：${ui.visits}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = ui.visits.toFloat().coerceIn(1f, 5000f),
                onValueChange = { viewModel.updateVisits(it.toInt()) },
                valueRange = 50f..5000f
            )

            OutlinedTextField(
                value = ui.komi.toString(),
                onValueChange = { viewModel.updateKomi(it.toFloatOrNull() ?: ui.komi) },
                label = { Text("贴目") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(onClick = { viewModel.save() }, modifier = Modifier.fillMaxWidth()) {
                Text("保存设置")
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val rel = ui.releaseResult
                    Text(
                        "对弈就绪：${if (ui.ready) "✅ 是，可直接开始对弈" else "❌ 否 — 还需选择 ${if (ui.engineAvailable) "权重文件" else "权重文件"}"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "KataGo 引擎：${
                            when {
                                ui.bundled && ui.engineAvailable -> "✅ 已随 APK 内置（无需手动选择）"
                                ui.bundled -> "⚠️ APK 内置了引擎但尚未释放 — 重启 App"
                                rel != null && rel.binaryReady -> "✅ 已释放：${rel.executablePath.substringAfterLast('/')}"
                                else -> "❌ 未内置 — 请通过「选取文件」按钮选择 KataGo ARM64 引擎"
                            }
                        }",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "libc++_shared.so：${
                            when {
                                ui.libcxxAvailable -> "✅ 已就绪（已随引擎释放）"
                                rel?.libcxxWasBundled == true -> "⚠️ APK 已内置但未释放 — 重启 App"
                                else -> "⚠️ 未内置 — 若自己选引擎，请确保同目录有 libc++_shared.so"
                            }
                        }",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "权重文件：${if (ui.modelPath.isNotEmpty()) "✅ 已选 ${ui.modelPath.substringAfterLast('/')}" else "❌ 未选择 — 请选择 KataGo 神经网络权重 (.bin.gz)"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            append("📖 使用流程（安装即用）：\n")
                            append("1) 引擎已随 APK 内置 → 不需要选。\n")
                            append("2) 选取权重文件（KataGo 官方 .bin.gz）。\n")
                            append("3) 点「保存设置」。\n")
                            append("4) 回到对弈页 → 点击「启动引擎」即可下棋。\n")
                            append("\n")
                            append("⚠️ 注意：引擎文件不能是 .so 共享库，必须是真正的 katago 可执行文件。\n")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            ui.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PathField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onPick: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = onPick) { Icon(Icons.Default.Folder, "选取文件") }
        }
    )
}
