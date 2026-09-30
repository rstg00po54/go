package com.badukai.app.ui.play

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badukai.app.analysis.AnalysisParser
import com.badukai.app.core.CoordinateUtils
import com.badukai.app.core.GameState
import com.badukai.app.core.IllegalMoveException
import com.badukai.app.core.Move
import com.badukai.app.core.Stone
import com.badukai.app.data.GameDatabase
import com.badukai.app.data.GameRepository
import com.badukai.app.engine.EngineManager
import com.badukai.app.gtp.EngineState
import com.badukai.app.gtp.GtpClient
import com.badukai.app.gtp.GtpCommand
import com.badukai.app.ui.board.BoardRenderModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 人机对弈页面的 UI 状态 */
data class PlayUiState(
    val board: BoardRenderModel = BoardRenderModel(size = 19, stones = emptyList()),
    val turn: Stone = Stone.BLACK,
    val blackName: String = "玩家",
    val whiteName: String = "AI",
    val aiThinking: Boolean = false,
    val engineReady: Boolean = false,
    val engineError: String? = null,
    val message: String? = null,
    val gameOver: Boolean = false,
    val scoreText: String? = null,
    val winrate: Float? = null,
    val showCoords: Boolean = true,
    val showSuggestions: Boolean = false,
)

/** 新对局参数 */
data class NewGameParams(
    val boardSize: Int = 19,
    val komi: Float = 7.5f,
    val handicap: Int = 0,
    val blackHuman: Boolean = true,
    val whiteHuman: Boolean = false,
    val blackName: String = "玩家",
    val whiteName: String = "AI",
)

/**
 * 人机对弈 ViewModel：管理 [GameState]、KataGo 引擎交互、提子/悔棋/数子与存档。
 */
class PlayViewModel(app: Application) : AndroidViewModel(app) {

    private val engineManager = EngineManager(app)
    private val repository = GameRepository(GameDatabase.get(app).gameDao())

    private var state: GameState = GameState()
    private var client: GtpClient? = null
    private var savedGameId: Long? = null

    private val _ui = MutableStateFlow(PlayUiState())
    val ui: StateFlow<PlayUiState> = _ui.asStateFlow()

    init {
        // 监听引擎配置变化，更新就绪状态
        viewModelScope.launch {
            engineManager.configFlow.collect { cfg ->
                _ui.update { it.copy(engineReady = engineManager.isEngineAvailable()) }
            }
        }
        newGame(NewGameParams())
    }

    /** 创建新对局 */
    fun newGame(params: NewGameParams) {
        state = GameState(
            boardSize = params.boardSize,
            komi = params.komi,
            handicap = params.handicap,
            blackHuman = params.blackHuman,
            whiteHuman = params.whiteHuman,
        )
        savedGameId = null
        // 引擎若已启动，重置棋盘大小并清空
        client?.let { c ->
            viewModelScope.launch {
                runCatching {
                    c.boardsize(params.boardSize)
                    c.komi(params.komi)
                    c.clear_board()
                    // 重放让子摆位
                    state.setupStones().forEach { (p, s) -> c.play(s, p, params.boardSize) }
                }
            }
        }
        refreshUi(message = "新对局已开始")
    }

    /** 玩家点击交点落子 */
    fun onHumanPlay(point: Int) {
        if (_ui.value.gameOver || _ui.value.aiThinking) return
        val turn = state.turn()
        val isHuman = (turn == Stone.BLACK && state.blackHuman) || (turn == Stone.WHITE && state.whiteHuman)
        if (!isHuman) return
        try {
            state.play(point)
        } catch (e: IllegalMoveException) {
            refreshUi(message = e.message); return
        }
        // 同步给引擎
        client?.let { c ->
            viewModelScope.launch {
                runCatching { c.play(turn, point, state.size) }
                afterMove()
            }
        } ?: afterMove()
    }

    /** 虚手 */
    fun pass() {
        if (_ui.value.gameOver || _ui.value.aiThinking) return
        val turn = state.turn()
        val isHuman = (turn == Stone.BLACK && state.blackHuman) || (turn == Stone.WHITE && state.whiteHuman)
        if (!isHuman) return
        state.pass()
        client?.let { c ->
            viewModelScope.launch { runCatching { c.play(turn, -1, state.size) }; afterMove() }
        } ?: afterMove()
    }

    /** 悔棋 */
    fun undo() {
        if (_ui.value.aiThinking) return
        // 悔到上一个人类可下点：若上一手是 AI，连退两手
        state.undoLast()
        val lastIsAiMove = state.movesList().isNotEmpty() &&
            !isHumanTurnFor(state.movesList().last())
        if (lastIsAiMove && state.movesList().isNotEmpty()) {
            state.undoLast()
        }
        client?.let { c ->
            viewModelScope.launch { resyncEngine(c) }
        }
        refreshUi()
    }

    /** 认输 */
    fun resign() {
        state.resign()
        refreshUi(message = "已认输", gameOver = true)
    }

    /** 请求 AI 落子（手动触发，或自动） */
    fun requestAiMove() {
        if (_ui.value.gameOver || _ui.value.aiThinking) return
        val turn = state.turn()
        val isHuman = (turn == Stone.BLACK && state.blackHuman) || (turn == Stone.WHITE && state.whiteHuman)
        if (isHuman) return
        if (client == null) {
            refreshUi(message = "引擎未启动，请在设置中配置 KataGo")
            return
        }
        viewModelScope.launch { aiMove() }
    }

    /** 切换坐标显示 */
    fun toggleCoords() = _ui.update { it.copy(showCoords = !it.showCoords) }

    /** 保存当前对局到数据库 */
    fun save(title: String) {
        viewModelScope.launch {
            val id = repository.save(
                state, title.ifBlank { "未命名对局" },
                _ui.value.blackName, _ui.value.whiteName,
                _ui.value.scoreText
            )
            savedGameId = id
            refreshUi(message = "已保存（#$id）")
        }
    }

    /** 启动引擎（若尚未启动） */
    fun ensureEngine() {
        if (client != null) return
        viewModelScope.launch {
            _ui.update { it.copy(message = "正在启动引擎…", engineError = null) }
            try {
                val cfg = engineManager.load()
                val c = engineManager.start(cfg, state.size)
                client = c
                resyncEngine(c)
                _ui.update { it.copy(engineReady = true, message = "引擎已就绪", engineError = null) }
            } catch (e: IllegalStateException) {
                // 引擎不可用 —— 显示引导信息，而不是崩溃
                _ui.update {
                    it.copy(
                        engineReady = false,
                        engineError = e.message ?: "引擎启动失败",
                        message = "请前往「设置」页配置 KataGo 引擎",
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        engineReady = false,
                        engineError = e.message,
                        message = "引擎启动异常：${e.message}\n请前往「设置」页检查引擎配置",
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        client?.close()
    }

    // -------- 内部 --------

    private suspend fun aiMove() {
        val c = client ?: return
        _ui.update { it.copy(aiThinking = true) }
        try {
            val turn = state.turn()
            val coord = c.genmove(turn, state.size)
            val point = CoordinateUtils.fromGtp(coord, state.size)
            if (coord.equals("resign", true)) {
                state.resign()
                refreshUi(message = "AI 认输", gameOver = true, aiThinking = false)
                return
            }
            if (coord.equals("pass", true) || point < 0) {
                state.pass()
            } else {
                state.play(point)
            }
            afterMoveInternal()
        } catch (e: Exception) {
            _ui.update { it.copy(aiThinking = false, message = "AI 落子失败：${e.message}") }
        }
    }

    /** 落子后处理：判断终局 / 触发 AI / 刷新 UI */
    private fun afterMove() {
        viewModelScope.launch { afterMoveInternal() }
    }

    private suspend fun afterMoveInternal() {
        if (state.isDoublePass()) {
            val (b, w) = state.score()
            val diff = b - w - state.komi
            val text = if (diff > 0) "黑+${String.format("%.1f", diff)}"
                       else "白+${String.format("%.1f", -diff)}"
            refreshUi(message = "对局结束（双方虚手）", gameOver = true, scoreText = text, aiThinking = false)
            return
        }
        val turn = state.turn()
        val isHumanNext = (turn == Stone.BLACK && state.blackHuman) || (turn == Stone.WHITE && state.whiteHuman)
        refreshUi(aiThinking = false)
        if (!isHumanNext && client != null && !_ui.value.gameOver) {
            aiMove()
        }
    }

    /** 把引擎棋盘同步到 [state] 当前位置 */
    private suspend fun resyncEngine(c: GtpClient) {
        runCatching {
            c.boardsize(state.size)
            c.komi(state.komi)
            c.clear_board()
            state.setupStones().forEach { (p, s) -> c.play(s, p, state.size) }
            for (rm in state.movesList().take(state.currentIndex())) {
                val stone = rm.stone ?: continue
                val m = rm.move
                if (m is Move.Play) c.play(stone, m.point, state.size)
                else if (m is Move.Pass) c.play(stone, -1, state.size)
            }
        }
    }

    private fun isHumanTurnFor(rm: com.badukai.app.core.RecordedMove): Boolean {
        val t = rm.stone ?: return false
        return (t == Stone.BLACK && state.blackHuman) || (t == Stone.WHITE && state.whiteHuman)
    }

    private fun refreshUi(
        message: String? = null,
        gameOver: Boolean = false,
        scoreText: String? = null,
        aiThinking: Boolean? = null,
    ) {
        val turn = state.turn()
        val board = BoardRenderModel.from(
            board = state.board(),
            lastMove = state.lastMovePoint(),
            showCoords = _ui.value.showCoords,
            interactive = true,
            showSuggestions = _ui.value.showSuggestions,
        )
        _ui.update { cur ->
            cur.copy(
                board = board,
                turn = turn,
                message = message ?: cur.message,
                gameOver = gameOver || cur.gameOver,
                scoreText = scoreText ?: cur.scoreText,
                aiThinking = aiThinking ?: cur.aiThinking,
            )
        }
    }

    /** 请求一次分析（更新候选选点叠加） */
    fun requestAnalysis() {
        val c = client ?: run {
            refreshUi(message = "引擎未启动"); return
        }
        viewModelScope.launch {
            try {
                val cmd = GtpCommand.analyze(intervalCentis = 10, kata = true)
                c.streamAnalyze(cmd).collect { line ->
                    val result = AnalysisParser.parse(line, state.turn())
                    if (result != null) {
                        val board = BoardRenderModel.from(
                            board = state.board(),
                            lastMove = state.lastMovePoint(),
                            analysis = result,
                            showCoords = _ui.value.showCoords,
                            interactive = true,
                            showSuggestions = true,
                        )
                        _ui.update { it.copy(board = board, winrate = result.rootWinrate) }
                    }
                }
            } catch (e: Exception) {
                refreshUi(message = "分析失败：${e.message}")
            }
        }
    }
}
