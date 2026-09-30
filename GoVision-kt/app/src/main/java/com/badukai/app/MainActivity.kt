package com.badukai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.badukai.app.ui.navigation.BadukNavHost
import com.badukai.app.ui.onboarding.OnboardingViewModel
import com.badukai.app.ui.onboarding.WeightPickerDialog
import com.badukai.app.ui.theme.BadukTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BadukTheme {
                BadukNavHost()
                val onboarding: OnboardingViewModel = viewModel()
                val state by onboarding.state.collectAsState()
                if (state.visible) {
                    when {
                        state.needsEngineSetup -> EngineSetupGuideDialog(
                            onDismiss = { onboarding.dismiss() }
                        )
                        state.needsWeightPick -> WeightPickerDialog(
                            onPicked = { uri -> onboarding.onWeightPicked(uri) },
                            onSkip = { onboarding.dismiss() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineSetupGuideDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("引擎未配置") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "欢迎使用 BadukAI！\n\n" +
                        "检测到尚未配置 KataGo 引擎。本应用需要 KataGo 引擎才能进行 AI 对弈和分析。\n\n" +
                        "请点击右上角 ⚙️ 设置图标，在「设置」页完成：\n" +
                        "1. 选取 KataGo ARM64 可执行文件\n" +
                        "   （从 KataGo 官方 Release 下载）\n" +
                        "2. 选取神经网络权重文件（.bin.gz）",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("知道了") }
        },
        dismissButton = null
    )
}
