package com.badukai.app.gtp

import com.badukai.app.core.CoordinateUtils
import com.badukai.app.core.Stone

/**
 * 常用 GTP 命令字符串构造器。
 *
 * 所有方法返回不含换行的命令字符串，由 [GtpClient.send] 负责追加换行并发送。
 * 坐标统一通过 [CoordinateUtils.toGtp] 转换为 GTP 风格（跳过字母 I，行号从底部 1 起）。
 */
object GtpCommand {

    /** 将 [Stone] 转为 GTP 颜色记号：黑 = "b"，白 = "w" */
    private fun Stone.color(): String = if (this == Stone.BLACK) "b" else "w"

    /** 生成一手棋：`genmove b|w` */
    fun genmove(color: Stone): String = "genmove ${color.color()}"

    /** 落子：`play b|w <coord>`。point 为 -1 时表示 pass。 */
    fun play(color: Stone, point: Int, size: Int): String {
        val coord = CoordinateUtils.toGtp(point, size)
        return "play ${color.color()} $coord"
    }

    /** 悔棋一手：`undo` */
    fun undo(): String = "undo"

    /** 设置棋盘大小：`boardsize n` */
    fun boardsize(n: Int): String = "boardsize $n"

    /** 清空棋盘：`clear_board` */
    fun clear_board(): String = "clear_board"

    /** 设置贴目：`komi k` */
    fun komi(k: Float): String = "komi $k"

    /** 列出引擎支持的命令：`list_commands` */
    fun list_commands(): String = "list_commands"

    /** 引擎名称：`name` */
    fun name(): String = "name"

    /** 引擎版本：`version` */
    fun version(): String = "version"

    /** GTP 协议版本：`protocol_version` */
    fun protocol_version(): String = "protocol_version"

    /** 查询某命令是否被支持：`known_command <cmd>` */
    fun known_command(command: String): String = "known_command $command"

    /** 显示棋盘（ASCII）：`showboard` */
    fun showboard(): String = "showboard"

    /** 终局数子：`final_score` */
    fun final_score(): String = "final_score"

    /** 时间规则：`time_settings <主时间> <读秒时间> <读秒内手数>`（单位：秒 / 手） */
    fun time_settings(mainTime: Int, byoYomiTime: Int, byoYomiStones: Int): String =
        "time_settings $mainTime $byoYomiTime $byoYomiStones"

    /** 某方剩余时间与读秒手数：`time_left b|w <时间> <手数>` */
    fun time_left(color: Stone, time: Int, stones: Int): String =
        "time_left ${color.color()} $time $stones"

    /**
     * 分析命令。
     * - KataGo 风格：`kata-analyze interval/true`（含 pv / scoreLead / ownership 等扩展信息）
     * - Leela Zero 风格：`lz-analyze interval`
     *
     * [intervalCentis] 为刷新间隔，单位厘秒（例如 100 = 每 1 秒输出一次）。
     * 注意：分析命令会持续流式输出多行，不应通过 [GtpClient.send] 等待单条响应，
     * 需由调用方直接读取引擎输出流并使用 [com.badukai.app.analysis.AnalysisParser] 逐行解析。
     */
    fun analyze(intervalCentis: Int, kata: Boolean = true): String =
        if (kata) "kata-analyze $intervalCentis/true" else "lz-analyze $intervalCentis"
}
