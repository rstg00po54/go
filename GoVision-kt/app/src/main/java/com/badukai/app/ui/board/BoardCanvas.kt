package com.badukai.app.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badukai.app.core.CoordinateUtils
import com.badukai.app.core.Stone
import com.badukai.app.ui.theme.AccentBlue
import com.badukai.app.ui.theme.AccentRed
import com.badukai.app.ui.theme.BoardLine
import com.badukai.app.ui.theme.BoardWood
import com.badukai.app.ui.theme.StoneBlack
import com.badukai.app.ui.theme.StoneWhite
import kotlin.math.min

/**
 * 围棋棋盘组件：绘制木盘、网格、星位、棋子、坐标、上一手标记与候选选点叠加；
 * 点击空交点回调 [onPointClick]（point 为一维索引）。
 */
@Composable
fun BoardCanvas(
    model: BoardRenderModel,
    onPointClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textColor = MaterialTheme.colorScheme.onBackground

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(BoardWood)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(model.interactive, model.size) {
                    if (!model.interactive) return@pointerInput
                    detectTapGestures { offset ->
                        val point = hitTest(offset.x, offset.y, size.width.toFloat(), size.height.toFloat(), model.size)
                        if (point >= 0) onPointClick(point)
                    }
                }
        ) {
            val canvasW = size.width
            val canvasH = size.height
            val n = model.size
            // 留出坐标标注空间
            val margin = if (model.showCoords) min(canvasW, canvasH) * 0.04f else min(canvasW, canvasH) * 0.025f
            val boardSize = min(canvasW, canvasH) - margin * 2
            val step = if (n > 1) boardSize / (n - 1) else boardSize
            val originX = (canvasW - boardSize) / 2f
            val originY = (canvasH - boardSize) / 2f

            drawGrid(originX, originY, step, n)
            drawStarPoints(originX, originY, step, n)
            if (model.showCoords) drawCoordinates(originX, originY, step, n, textColor)
            drawStones(model.stones, originX, originY, step, n)
            drawLastMove(model.lastMove, originX, originY, step, n)
            if (model.showSuggestions) drawSuggestions(model.analysis, originX, originY, step, n)
        }
    }
}

/** 点击命中检测：返回最近交点的一维索引；偏离过远返回 -1 */
private fun hitTest(x: Float, y: Float, w: Float, h: Float, n: Int): Int {
    val margin = min(w, h) * 0.04f
    val boardSize = min(w, h) - margin * 2
    val step = if (n > 1) boardSize / (n - 1) else boardSize
    val originX = (w - boardSize) / 2f
    val originY = (h - boardSize) / 2f
    val col = ((x - originX + step / 2f) / step).toInt()
    val row = ((y - originY + step / 2f) / step).toInt()
    if (col < 0 || col >= n || row < 0 || row >= n) return -1
    // 容差：点击点距交点不超过半格
    val cx = originX + col * step
    val cy = originY + row * step
    val dx = x - cx
    val dy = y - cy
    if (dx * dx + dy * dy > (step / 1.5f) * (step / 1.5f) * 1f) return -1
    return row * n + col
}

private fun DrawScope.drawGrid(ox: Float, oy: Float, step: Float, n: Int) {
    val lineColor = BoardLine
    for (i in 0 until n) {
        val pos = i * step
        drawLine(lineColor, Offset(ox, oy + pos), Offset(ox + step * (n - 1), oy + pos), 1.2f)
        drawLine(lineColor, Offset(ox + pos, oy), Offset(ox + pos, oy + step * (n - 1)), 1.2f)
    }
    // 外框略粗
    drawLine(lineColor, Offset(ox, oy), Offset(ox + step * (n - 1), oy), 2f)
    drawLine(lineColor, Offset(ox, oy + step * (n - 1)), Offset(ox + step * (n - 1), oy + step * (n - 1)), 2f)
    drawLine(lineColor, Offset(ox, oy), Offset(ox, oy + step * (n - 1)), 2f)
    drawLine(lineColor, Offset(ox + step * (n - 1), oy), Offset(ox + step * (n - 1), oy + step * (n - 1)), 2f)
}

private fun DrawScope.drawStarPoints(ox: Float, oy: Float, step: Float, n: Int) {
    val r = step * 0.06f
    for ((r0, c0) in starPoints(n)) {
        drawCircle(BoardLine, radius = r, center = Offset(ox + c0 * step, oy + r0 * step))
    }
}

private fun DrawScope.drawCoordinates(ox: Float, oy: Float, step: Float, n: Int, color: Color) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = step * 0.45f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val fm = paint.fontMetrics
    val half = (fm.ascent + fm.descent) / 2f
    for (i in 0 until n) {
        val pos = i * step
        // 顶部字母
        drawContext.canvas.nativeCanvas.drawText(
            GtpLetters[i].toString(), ox + pos, oy - step * 0.35f - half, paint
        )
        // 左侧数字（从下到上 1..n）
        drawContext.canvas.nativeCanvas.drawText(
            (n - i).toString(), ox - step * 0.35f, oy + pos - half, paint
        )
    }
}

private fun DrawScope.drawStones(
    stones: List<Pair<Int, Stone>>, ox: Float, oy: Float, step: Float, n: Int
) {
    val radius = step * 0.46f
    for ((point, stone) in stones) {
        val col = point % n
        val row = point / n
        val cx = ox + col * step
        val cy = oy + row * step
        // 阴影
        drawCircle(Color.Black.copy(alpha = 0.25f), radius = radius,
            center = Offset(cx + radius * 0.12f, cy + radius * 0.18f))
        val base = if (stone == Stone.BLACK) StoneBlack else StoneWhite
        val edge = if (stone == Stone.BLACK) Color(0xFF000000) else Color(0xFFBDBDBD)
        drawCircle(base, radius = radius, center = Offset(cx, cy))
        // 描边
        drawCircle(edge, radius = radius, center = Offset(cx, cy), style = Stroke(width = radius * 0.08f))
        // 高光
        drawCircle(Color.White.copy(alpha = if (stone == Stone.BLACK) 0.18f else 0.6f),
            radius = radius * 0.32f, center = Offset(cx - radius * 0.28f, cy - radius * 0.32f))
    }
}

private fun DrawScope.drawLastMove(point: Int, ox: Float, oy: Float, step: Float, n: Int) {
    if (point < 0) return
    val col = point % n
    val row = point / n
    val cx = ox + col * step
    val cy = oy + row * step
    drawCircle(AccentRed, radius = step * 0.14f, center = Offset(cx, cy))
}

private fun DrawScope.drawSuggestions(
    analysis: com.badukai.app.analysis.AnalysisResult?,
    ox: Float, oy: Float, step: Float, n: Int
) {
    analysis ?: return
    val maxVisits = analysis.moveInfos.maxOfOrNull { it.visits } ?: 1
    val radius = step * 0.30f
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = step * 0.32f
        color = android.graphics.Color.WHITE
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    // 仅绘制前若干候选，避免画面过乱
    analysis.moveInfos.take(5).forEachIndexed { idx, mv ->
        val p = CoordinateUtils.fromGtp(mv.move, n)
        if (p < 0) return@forEachIndexed
        val col = p % n
        val row = p / n
        val cx = ox + col * step
        val cy = oy + row * step
        val alpha = 0.35f + 0.55f * (mv.visits.toFloat() / maxVisits)
        val color = when (idx) {
            0 -> AccentBlue
            1 -> Color(0xFF2E7D32)
            2 -> Color(0xFF6A1B9A)
            3 -> Color(0xFFEF6C00)
            else -> Color(0xFF546E7A)
        }.copy(alpha = alpha)
        drawCircle(color, radius = radius, center = Offset(cx, cy))
        drawCircle(Color.White, radius = radius, center = Offset(cx, cy), style = Stroke(width = radius * 0.12f))
        val label = "${(mv.winrate).toInt()}"
        val fm = paint.fontMetrics
        drawContext.canvas.nativeCanvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
    }
}
