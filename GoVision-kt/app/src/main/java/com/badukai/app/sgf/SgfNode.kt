package com.badukai.app.sgf

/**
 * SGF（Smart Game Format）数据模型。
 *
 * SGF 以"游戏树"组织：一棵树由若干 [SgfNode] 串成，节点可挂多棵子树形成分支。
 * 每个节点持有若干 [SgfProperty]，属性形如 `ID[value1][value2]...`。
 */

/** SGF 属性：一个标识符（大写字母，如 B/W/SZ/KM/AB）与零到多个值 */
data class SgfProperty(val id: String, val values: List<String>)

/**
 * SGF 节点：含若干属性与零到多个子节点。
 * 子节点列表为 [children]：单子表示主行棋序列，多子表示分支变化图。
 */
data class SgfNode(
    val properties: MutableList<SgfProperty> = mutableListOf(),
    val children: MutableList<SgfNode> = mutableListOf()
) {
    /** 取指定属性的首个值；不存在返回 null */
    fun prop(id: String): String? =
        properties.firstOrNull { it.id == id }?.values?.firstOrNull()

    /** 追加一个单值属性（便捷写法） */
    fun addProp(id: String, value: String) {
        properties.add(SgfProperty(id, listOf(value)))
    }
}

/**
 * SGF 集合：一份 .sgf 文件可包含一或多棵独立游戏树。
 * 通常只用第一棵 [games] 作为主谱。
 */
data class SgfCollection(val games: List<SgfNode>)
