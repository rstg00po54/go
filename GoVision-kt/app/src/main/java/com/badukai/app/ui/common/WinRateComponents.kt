package com.badukai.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badukai.app.ui.theme.StoneBlack
import com.badukai.app.ui.theme.StoneWhite

/**
 * 胜率条：左侧黑、右侧白，[blackWinrate] 为 0-100 的黑方胜率。
 */
@Composable
fun WinRateBar(blackWinrate: Float, modifier: Modifier = Modifier) {
    val black = blackWinrate.coerceIn(0f, 100f)
    val white = 100f - black
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(12.dp).clip(CircleShape).background(StoneBlack)
            )
            Text(
                "黑 ${black.toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                "白 ${white.toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp)
            )
            Box(
                Modifier.size(12.dp).clip(CircleShape).background(StoneWhite)
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(CircleShape)
                .background(StoneWhite)
        ) {
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val w = size.width
                val barW = w * (black / 100f)
                drawRect(StoneBlack, Offset.Zero, Size(barW, size.height))
            }
        }
    }
}

/**
 * 胜率曲线：按步序绘制黑方胜率折线，[points] 为每步的黑胜率(0-100)。
 */
@Composable
fun WinRateChart(
    points: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = StoneBlack,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(90.dp)
            .padding(horizontal = 8.dp)
    ) {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            if (points.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            val dx = w / (points.size - 1).coerceAtLeast(1)
            // 50% 基线
            drawLine(
                color = Color.Gray.copy(alpha = 0.4f),
                start = Offset(0f, h / 2f),
                end = Offset(w, h / 2f),
                strokeWidth = 1f
            )
            val path = Path()
            points.forEachIndexed { i, wr ->
                val x = i * dx.toFloat()
                val y = h * (1f - wr / 100f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
    }
}
