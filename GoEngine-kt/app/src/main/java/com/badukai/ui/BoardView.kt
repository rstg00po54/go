package com.badukai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.badukai.game.GoBoard
import com.badukai.game.Intersection
import com.badukai.game.Point
import kotlin.math.roundToInt

/**
 * Minimalist Go board component
 */
@Composable
fun GoBoard(
    board: GoBoard,
    lastMovePoint: Point?,
    onIntersectionTap: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val boardSize = board.size
    val density = LocalDensity.current
    
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(1f)
            .background(BadukaiColors.BoardBackground)
    ) {
        val sizePx = with(density) { 
            kotlin.math.min(maxWidth.toPx(), maxHeight.toPx()) 
        }
        val padding = sizePx * 0.04f
        val boardSizePx = sizePx - padding * 2f
        val cellSize = boardSizePx / (boardSize - 1).toFloat()
        
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (enabled) {
                        detectTapGestures { offset ->
                            val x = ((offset.x - padding) / cellSize).roundToInt()
                                .coerceIn(0, boardSize - 1)
                            val y = ((offset.y - padding) / cellSize).roundToInt()
                                .coerceIn(0, boardSize - 1)
                            onIntersectionTap(x, y)
                        }
                    }
                }
        ) {
            // Draw grid lines
            drawGrid(boardSize, padding, cellSize)
            
            // Draw star points (hoshi)
            drawStarPoints(boardSize, padding, cellSize)
            
            // Draw stones
            drawStones(board, boardSize, padding, cellSize, lastMovePoint)
        }
    }
}

private fun DrawScope.drawGrid(
    boardSize: Int,
    padding: Float,
    cellSize: Float
) {
    val lineWidth = 1.5f
    
    for (i in 0 until boardSize) {
        val pos = padding + i * cellSize
        
        // Vertical line
        drawLine(
            color = BadukaiColors.BoardLine,
            start = Offset(pos, padding),
            end = Offset(pos, padding + (boardSize - 1) * cellSize),
            strokeWidth = lineWidth
        )
        
        // Horizontal line
        drawLine(
            color = BadukaiColors.BoardLine,
            start = Offset(padding, pos),
            end = Offset(padding + (boardSize - 1) * cellSize, pos),
            strokeWidth = lineWidth
        )
    }
}

private fun DrawScope.drawStarPoints(
    boardSize: Int,
    padding: Float,
    cellSize: Float
) {
    val starPoints = getStarPoints(boardSize)
    val radius = cellSize * 0.12f
    
    for (point in starPoints) {
        val x = padding + point.x * cellSize
        val y = padding + point.y * cellSize
        
        drawCircle(
            color = BadukaiColors.StarPoint,
            radius = radius,
            center = Offset(x, y)
        )
    }
}

private fun getStarPoints(boardSize: Int): List<Point> {
    return when (boardSize) {
        19 -> listOf(
            Point(3, 3), Point(9, 3), Point(15, 3),
            Point(3, 9), Point(9, 9), Point(15, 9),
            Point(3, 15), Point(9, 15), Point(15, 15)
        )
        13 -> listOf(
            Point(3, 3), Point(6, 3), Point(9, 3),
            Point(3, 6), Point(6, 6), Point(9, 6),
            Point(3, 9), Point(6, 9), Point(9, 9)
        )
        9 -> listOf(
            Point(2, 2), Point(4, 2), Point(6, 2),
            Point(2, 4), Point(4, 4), Point(6, 4),
            Point(2, 6), Point(4, 6), Point(6, 6)
        )
        else -> emptyList()
    }
}

private fun DrawScope.drawStones(
    board: GoBoard,
    boardSize: Int,
    padding: Float,
    cellSize: Float,
    lastMovePoint: Point?
) {
    val stoneRadius = cellSize * 0.45f
    
    for (y in 0 until boardSize) {
        for (x in 0 until boardSize) {
            val intersection = board.get(x, y)
            if (intersection == Intersection.EMPTY) continue
            
            val centerX = padding + x * cellSize
            val centerY = padding + y * cellSize
            val center = Offset(centerX, centerY)
            val isLastMove = lastMovePoint?.x == x && lastMovePoint.y == y
            
            when (intersection) {
                Intersection.BLACK -> {
                    // Black stone with subtle gradient effect
                    drawCircle(
                        color = BadukaiColors.BlackStone,
                        radius = stoneRadius,
                        center = center
                    )
                    // Subtle highlight
                    drawCircle(
                        color = BadukaiColors.BlackStoneHighlight.copy(alpha = 0.3f),
                        radius = stoneRadius * 0.3f,
                        center = Offset(centerX - stoneRadius * 0.25f, centerY - stoneRadius * 0.25f)
                    )
                }
                Intersection.WHITE -> {
                    // White stone with border
                    drawCircle(
                        color = BadukaiColors.WhiteStone,
                        radius = stoneRadius,
                        center = center
                    )
                    drawCircle(
                        color = BadukaiColors.WhiteStoneBorder,
                        radius = stoneRadius,
                        center = center,
                        style = Stroke(width = 1.5f)
                    )
                    // Subtle highlight
                    drawCircle(
                        color = BadukaiColors.WhiteStoneHighlight.copy(alpha = 0.5f),
                        radius = stoneRadius * 0.25f,
                        center = Offset(centerX - stoneRadius * 0.2f, centerY - stoneRadius * 0.2f)
                    )
                }
                Intersection.EMPTY -> {}
            }
            
            // Last move marker
            if (isLastMove) {
                val markerColor = if (intersection == Intersection.BLACK) 
                    BadukaiColors.WhiteStone else BadukaiColors.BlackStone
                
                drawCircle(
                    color = markerColor,
                    radius = stoneRadius * 0.25f,
                    center = center
                )
            }
        }
    }
}
