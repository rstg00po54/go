package com.badukai.app.ui.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 权重选择对话框：引导用户选取 KataGo 权重文件（.bin.gz）。
 * 仅当引擎已就绪但权重未配置时显示。
 */
@Composable
fun WeightPickerDialog(
    title: String = "选择权重文件",
    onPicked: (Uri) -> Unit,
    onSkip: () -> Unit,
) {
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { pickedUri = uri; onPicked(uri) }
    }

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "KataGo 引擎已就绪，只需选择一个神经网络权重文件即可开始对弈。\n\n" +
                        "权重文件通常是 .bin.gz 格式（例如 b18c384nbt-420m.bin.gz），" +
                        "可从 KataGo 官方发布页或社区获取后存放到手机。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start
                )
                Button(
                    onClick = { launcher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (pickedUri == null) "选择权重文件" else "已选择，正在配置…")
                }
                if (pickedUri != null) {
                    Text(
                        pickedUri.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { OutlinedButton(onClick = onSkip) { Text("稍后") } }
    )
}
