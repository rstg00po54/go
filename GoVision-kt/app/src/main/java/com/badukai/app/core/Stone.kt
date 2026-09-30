package com.badukai.app.core

/** 棋子颜色（空点用 null 表示） */
enum class Stone { BLACK, WHITE }

/** 落子动作 */
sealed class Move {
    /** 普通落子，point 取值 0..size*size-1，-1 表示 pass */
    data class Play(val point: Int) : Move()
    /** 虚手 */
    object Pass : Move()
    /** 认输 */
    object Resign : Move()
}

/** 坐标工具：GTP 字母列（跳过 I）与一维索引互转 */
object CoordinateUtils {
    private val LETTERS = charArrayOf(
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M', 'N',
        'O', 'P', 'Q', 'R', 'S', 'T'
    )

    fun letter(col: Int): Char {
        require(col in LETTERS.indices) { "col out of range: $col" }
        return LETTERS[col]
    }

    fun col(letter: Char): Int {
        val upper = letter.uppercaseChar()
        val idx = LETTERS.indexOf(upper)
        require(idx >= 0) { "invalid letter: $letter" }
        return idx
    }

    /** point -> "Q16" 形式（GTP 风格，行号从底部 1 起） */
    fun toGtp(point: Int, size: Int): String {
        if (point < 0) return "pass"
        val col = point % size
        val row = point / size
        return "${letter(col)}${size - row}"
    }

    /** "Q16" -> point；非法返回 -1 */
    fun fromGtp(text: String, size: Int): Int {
        val t = text.trim()
        if (t.equals("pass", true) || t.equals("resign", true) || t == "tt" && size <= 19) return -1
        if (t.length < 2) return -1
        val c = t[0]
        val rowStr = t.substring(1)
        val rowNum = rowStr.toIntOrNull() ?: return -1
        val col = runCatching { col(c) }.getOrNull() ?: return -1
        if (col < 0 || col >= size) return -1
        val row = size - rowNum
        if (row < 0 || row >= size) return -1
        return row * size + col
    }
}
