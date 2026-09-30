package com.badukai.app.sgf

/**
 * SGF 递归下降解析器。
 *
 * 支持标准 SGF 文法：
 * - `(` 树开始、`)` 树结束、`;` 节点开始
 * - 属性形如 `ID[value1][value2]`，ID 为大写字母
 * - 一个节点可有多个属性、一棵树可有多个节点、子树可嵌套
 * - value 中反转义 `\]` `\\` `\n` `\t` 等
 *
 * 容错策略：遇到非法字符尽量跳过继续，不抛异常；解析失败返回空集合或已解析部分。
 */
object SgfParser {

    /**
     * 解析整段 SGF 文本，返回 [SgfCollection]。
     * 顶层可包含多棵游戏树；非法前导字符会被跳过直到遇到 `(`。
     */
    fun parse(text: String): SgfCollection {
        val cursor = Cursor(text)
        val games = ArrayList<SgfNode>()
        while (cursor.hasNext()) {
            cursor.skipWhitespace()
            if (!cursor.hasNext()) break
            if (cursor.peek() != '(') {
                // 顶层非法字符，跳过继续寻找下一棵树
                cursor.next()
                continue
            }
            val tree = parseGameTree(cursor)
            if (tree != null) games.add(tree)
        }
        return SgfCollection(games)
    }

    /**
     * 解析单手棋文本，返回一维坐标 point。
     * 接受形如 "B[qq]" / "W[aa]" 的属性串，也接受裸坐标 "qq"。
     * pass：输入为 "" / "tt"(size<=19) / "B[]" 时返回 -1。
     */
    fun parseMove(text: String, size: Int): Int {
        val t = text.trim()
        if (t.isEmpty()) return -1
        // 若带方括号则取括号内坐标，否则视整段为坐标
        val start = t.indexOf('[')
        val end = t.indexOf(']')
        val coord = if (start >= 0 && end > start) t.substring(start + 1, end) else t
        return sgfCoordToPoint(coord, size)
    }

    // ---- 内部解析实现 ----

    /** 解析一棵子树：'(' (节点 / 子树)* ')'，返回树中首节点 */
    private fun parseGameTree(cursor: Cursor): SgfNode? {
        if (cursor.next() != '(') return null // 已在调用前 peek 过，此处安全消费
        var firstNode: SgfNode? = null
        var current: SgfNode? = null
        while (cursor.hasNext()) {
            cursor.skipWhitespace()
            if (!cursor.hasNext()) break // 未闭合也算解析完毕
            when (val c = cursor.peek()) {
                ')' -> { cursor.next(); break } // 树结束
                ';' -> {
                    cursor.next() // 消费 ';'
                    val node = parseNode(cursor)
                    if (firstNode == null) {
                        firstNode = node
                        current = node
                    } else {
                        current!!.children.add(node)
                        current = node
                    }
                }
                '(' -> {
                    // 嵌套子树，挂到当前节点作为分支
                    val sub = parseGameTree(cursor)
                    if (sub != null && current != null) current.children.add(sub)
                }
                else -> { cursor.next() } // 非法字符跳过
            }
        }
        return firstNode
    }

    /** 解析 ';' 之后的属性序列，直到遇到 ';' / ')' / '(' 或文本结束 */
    private fun parseNode(cursor: Cursor): SgfNode {
        val node = SgfNode()
        while (cursor.hasNext()) {
            cursor.skipWhitespace()
            if (!cursor.hasNext()) break
            val c = cursor.peek()
            if (c == ';' || c == ')' || c == '(') break
            if (!c.isUpperCase()) { cursor.next(); continue } // 非法字符跳过
            val prop = parseProperty(cursor) ?: continue
            node.properties.add(prop)
        }
        return node
    }

    /** 解析一个属性：大写 ID + 一个或多个 [value] */
    private fun parseProperty(cursor: Cursor): SgfProperty? {
        val idBuilder = StringBuilder()
        while (cursor.hasNext() && cursor.peek().isUpperCase()) {
            idBuilder.append(cursor.next())
        }
        if (idBuilder.isEmpty()) return null
        val id = idBuilder.toString()
        val values = ArrayList<String>()
        while (cursor.hasNext()) {
            cursor.skipWhitespace()
            if (!cursor.hasNext() || cursor.peek() != '[') break
            cursor.next() // 消费 '['
            values.add(parseValue(cursor))
        }
        if (values.isEmpty()) values.add("") // 容错：无值属性给空串
        return SgfProperty(id, values)
    }

    /** 解析 '[' 之后的属性值，处理反转义，直到未转义的 ']' */
    private fun parseValue(cursor: Cursor): String {
        val sb = StringBuilder()
        while (cursor.hasNext()) {
            val c = cursor.next()
            if (c == '\\') {
                if (!cursor.hasNext()) break
                when (val e = cursor.next()) {
                    ']' -> sb.append(']')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(e) // 其他转义保留字符本身
                }
            } else if (c == ']') {
                return sb.toString()
            } else {
                sb.append(c)
            }
        }
        return sb.toString() // 未闭合的 value，返回已读部分
    }

    /** SGF 坐标串 -> point；"" 或 "tt"(size<=19) 视为 pass 返回 -1 */
    private fun sgfCoordToPoint(coord: String, size: Int): Int {
        val c = coord.trim()
        if (c.isEmpty()) return -1
        if (c == "tt" && size <= 19) return -1
        if (c.length < 2) return -1
        val colChar = c[0]
        val rowChar = c[1]
        if (colChar < 'a' || colChar > 's' || rowChar < 'a' || rowChar > 's') return -1
        val col = colChar - 'a'
        val row = rowChar - 'a'
        if (col >= size || row >= size) return -1
        return row * size + col
    }

    /** 简易游标，封装位置索引 */
    private class Cursor(val text: String) {
        var pos: Int = 0
        fun hasNext(): Boolean = pos < text.length
        fun peek(): Char = text[pos]
        fun next(): Char = text[pos++]
        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
