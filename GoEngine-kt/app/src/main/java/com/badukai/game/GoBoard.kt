package com.badukai.game

/**
 * Represents a position on the Go board
 */
data class Point(val x: Int, val y: Int) {
    /**
     * Convert to GTP coordinate format (e.g., "D4", "Q16")
     * Note: GTP skips 'I' to avoid confusion with 'J'
     */
    fun toGtp(boardSize: Int): String {
        val letters = "ABCDEFGHJKLMNOPQRST" // No 'I'
        val col = letters[x]
        val row = boardSize - y
        return "$col$row"
    }

    companion object {
        /**
         * Parse a GTP coordinate (e.g., "D4") to a Point
         */
        fun fromGtp(gtp: String, boardSize: Int): Point? {
            if (gtp.length < 2) return null
            
            val letters = "ABCDEFGHJKLMNOPQRST"
            val col = gtp[0].uppercaseChar()
            val colIndex = letters.indexOf(col)
            if (colIndex < 0) return null
            
            val row = gtp.substring(1).toIntOrNull() ?: return null
            val y = boardSize - row
            
            if (colIndex >= boardSize || y < 0 || y >= boardSize) return null
            
            return Point(colIndex, y)
        }
    }
}

/**
 * Stone color
 */
enum class StoneColor {
    BLACK, WHITE;

    fun opposite(): StoneColor = if (this == BLACK) WHITE else BLACK
    
    fun toGtp(): String = if (this == BLACK) "black" else "white"
}

/**
 * Represents the state of a single intersection
 */
enum class Intersection {
    EMPTY, BLACK, WHITE;
    
    fun toStoneColor(): StoneColor? = when (this) {
        BLACK -> StoneColor.BLACK
        WHITE -> StoneColor.WHITE
        EMPTY -> null
    }
}

/**
 * Represents a move in the game
 */
sealed class Move {
    data class Stone(val point: Point, val color: StoneColor) : Move()
    data class Pass(val color: StoneColor) : Move()
    data class Resign(val color: StoneColor) : Move()
}

/**
 * Go board with game logic
 */
class GoBoard(val size: Int = 19) {
    
    private val board: Array<Array<Intersection>> = Array(size) { Array(size) { Intersection.EMPTY } }
    private val moveHistory = mutableListOf<Move>()
    private var koPoint: Point? = null
    private var capturedBlack = 0
    private var capturedWhite = 0
    private var consecutivePasses = 0
    
    val isGameOver: Boolean
        get() = consecutivePasses >= 2 || moveHistory.lastOrNull() is Move.Resign

    fun get(x: Int, y: Int): Intersection = board[y][x]
    fun get(point: Point): Intersection = board[point.y][point.x]

    /**
     * Check if a move is legal
     */
    fun isLegalMove(point: Point, color: StoneColor): Boolean {
        if (get(point) != Intersection.EMPTY) return false
        if (point == koPoint) return false
        
        // Check if move would have liberties or capture
        val testBoard = copy()
        testBoard.placeStone(point, color)
        val captures = testBoard.removeDeadStones(color.opposite())
        
        // Check if the stone itself has liberties after captures
        val group = testBoard.getGroup(point)
        return testBoard.hasLiberty(group) || captures.isNotEmpty()
    }

    /**
     * Play a move
     */
    fun playMove(move: Move): List<Point> {
        return when (move) {
            is Move.Stone -> playStone(move.point, move.color)
            is Move.Pass -> {
                consecutivePasses++
                moveHistory.add(move)
                koPoint = null
                emptyList()
            }
            is Move.Resign -> {
                moveHistory.add(move)
                emptyList()
            }
        }
    }

    private fun playStone(point: Point, color: StoneColor): List<Point> {
        consecutivePasses = 0
        placeStone(point, color)
        moveHistory.add(Move.Stone(point, color))
        
        val captured = removeDeadStones(color.opposite())
        
        // Update capture counts
        when (color) {
            StoneColor.BLACK -> capturedWhite += captured.size
            StoneColor.WHITE -> capturedBlack += captured.size
        }
        
        // Check for ko
        koPoint = if (captured.size == 1) {
            val capturedPoint = captured.first()
            val group = getGroup(point)
            if (group.size == 1 && countLiberties(group) == 1) {
                capturedPoint
            } else null
        } else null
        
        return captured
    }

    private fun placeStone(point: Point, color: StoneColor) {
        board[point.y][point.x] = if (color == StoneColor.BLACK) Intersection.BLACK else Intersection.WHITE
    }

    private fun removeStone(point: Point) {
        board[point.y][point.x] = Intersection.EMPTY
    }

    private fun removeDeadStones(color: StoneColor): List<Point> {
        val removed = mutableListOf<Point>()
        val intersection = if (color == StoneColor.BLACK) Intersection.BLACK else Intersection.WHITE
        val visited = mutableSetOf<Point>()

        for (y in 0 until size) {
            for (x in 0 until size) {
                val point = Point(x, y)
                if (get(point) == intersection && point !in visited) {
                    val group = getGroup(point)
                    visited.addAll(group)
                    if (!hasLiberty(group)) {
                        for (p in group) {
                            removeStone(p)
                            removed.add(p)
                        }
                    }
                }
            }
        }

        return removed
    }

    private fun getGroup(start: Point): Set<Point> {
        val color = get(start)
        if (color == Intersection.EMPTY) return emptySet()

        val group = mutableSetOf<Point>()
        val queue = ArrayDeque<Point>()
        queue.add(start)

        while (queue.isNotEmpty()) {
            val point = queue.removeFirst()
            if (point in group) continue
            if (get(point) != color) continue

            group.add(point)
            getNeighbors(point).forEach { queue.add(it) }
        }

        return group
    }

    private fun hasLiberty(group: Set<Point>): Boolean {
        return group.any { point ->
            getNeighbors(point).any { get(it) == Intersection.EMPTY }
        }
    }

    private fun countLiberties(group: Set<Point>): Int {
        return group.flatMap { getNeighbors(it) }
            .filter { get(it) == Intersection.EMPTY }
            .toSet()
            .size
    }

    private fun getNeighbors(point: Point): List<Point> {
        return listOf(
            Point(point.x - 1, point.y),
            Point(point.x + 1, point.y),
            Point(point.x, point.y - 1),
            Point(point.x, point.y + 1)
        ).filter { it.x in 0 until size && it.y in 0 until size }
    }

    /**
     * Undo the last move
     */
    fun undo(): Boolean {
        // For simplicity, we rebuild from history minus last move
        if (moveHistory.isEmpty()) return false
        
        val history = moveHistory.toList().dropLast(1)
        clear()
        
        for (move in history) {
            playMove(move)
        }
        
        return true
    }

    /**
     * Clear the board
     */
    fun clear() {
        for (y in 0 until size) {
            for (x in 0 until size) {
                board[y][x] = Intersection.EMPTY
            }
        }
        moveHistory.clear()
        koPoint = null
        capturedBlack = 0
        capturedWhite = 0
        consecutivePasses = 0
    }

    /**
     * Create a copy of this board
     */
    fun copy(): GoBoard {
        val newBoard = GoBoard(size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                newBoard.board[y][x] = board[y][x]
            }
        }
        return newBoard
    }

    /**
     * Get all legal moves for a color
     */
    fun getLegalMoves(color: StoneColor): List<Point> {
        val moves = mutableListOf<Point>()
        for (y in 0 until size) {
            for (x in 0 until size) {
                val point = Point(x, y)
                if (isLegalMove(point, color)) {
                    moves.add(point)
                }
            }
        }
        return moves
    }

    fun getCapturedBlack(): Int = capturedBlack
    fun getCapturedWhite(): Int = capturedWhite
    fun getMoveCount(): Int = moveHistory.size
    fun getLastMove(): Move? = moveHistory.lastOrNull()
}
