package com.badukai.app.ui.analysis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badukai.app.analysis.AnalysisParser
import com.badukai.app.analysis.AnalysisResult
import com.badukai.app.core.GameState
import com.badukai.app.core.Move
import com.badukai.app.core.Stone
import com.badukai.app.data.GameDatabase
import com.badukai.app.data.GameRepository
import com.badukai.app.engine.EngineManager
import com.badukai.app.gtp.GtpClient
import com.badukai.app.gtp.GtpCommand
import com.badukai.app.sgf.SgfGameLoader
import com.badukai.app.ui.board.BoardRenderModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 分析复盘页面 UI 状态 */
data class AnalysisUiState(
    val board: BoardRenderModel = BoardRenderModel(size = 19, stones = emptyList()),
    val currentIndex: Int = 0,
    val totalMoves: Int = 0,
    val winrate: Float? = null,
    /** 每一手的黑方胜率（已分析的位置才有值，未分析为 null） */
    val winrateHistory: Map<Int, Float> = emptyMap(),
    val analysis: AnalysisResult? = null,
    val analyzing: Boolean = false,
    val batchProgress: Float = 0f,
    val message: String? = null,
    val engineReady: Boolean = false,
    val gameTitle: String = "未加载棋谱",
    val showCoords: Boolean = true,
)

/**
 * 分析复盘 ViewModel：加载棋谱、回放导航、调用 KataGo 流式分析当前局面、
 * 支持整盘批量分析以生成胜率曲线。
 */
class AnalysisViewModel(app: Application) : AndroidViewModel(app) {

    private val engineManager = EngineManager(app)
    private val repository = GameRepository(GameDatabase.get(app).gameDao())

    private var state: GameState = GameState(blackHuman = true, whiteHuman = true)
    private var client: GtpClient? = null
    private var analyzeJob: Job? = null

    private val _ui = MutableStateFlow(AnalysisUiState())
    val ui: StateFlow<AnalysisUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            engineManager.configFlow.collect { _ui.update { it.copy(engineReady = engineManager.isEngineAvailable()) } }
        }
        refreshBoard()
    }

    /** 从 SGF 文本加载棋谱 */
    fun loadFromSgf(text: String, title: String = "导入棋谱") {
        analyzeJob?.cancel()
        state = SgfGameLoader.load(text)
        _ui.update {
            it.copy(winrateHistory = emptyMap(), winrate = null, analysis = null, gameTitle = title)
        }
        refreshBoard()
    }

    /** 从数据库加载某局 */
    fun loadFromGameId(id: Long) {
        viewModelScope.launch {
            val entity = repository.getById(id) ?: return@launch
            loadFromSgf(entity.sgf, entity.title)
        }
    }

    // -------- 导航 --------

    fun goto(index: Int) { state.gotoIndex(index); refreshBoard() }
    fun next() { if (state.currentIndex() < state.totalMoves()) { state.gotoIndex(state.currentIndex() + 1); refreshBoard() } }
    fun prev() { if (state.currentIndex() > 0) { state.gotoIndex(state.currentIndex() - 1); refreshBoard() } }
    fun first() { state.gotoIndex(0); refreshBoard() }
    fun last() { state.gotoIndex(state.totalMoves()); refreshBoard() }

    fun toggleCoords() = _ui.update { it.copy(showCoords = !it.showCoords) }

    // -------- 引擎 --------

    fun ensureEngine() {
        if (client != null) return
        viewModelScope.launch {
            _ui.update { it.copy(message = "正在启动引擎…") }
            try {
                val cfg = engineManager.load()
                val c = engineManager.start(cfg, state.size)
                client = c
                resyncEngine(c)
                _ui.update { it.copy(engineReady = true, message = "引擎已就绪") }
            } catch (e: IllegalStateException) {
                _ui.update {
                    it.copy(message = "引擎启动失败：${e.message}\n请前往「设置」页配置 KataGo 引擎")
                }
            } catch (e: Exception) {
                _ui.update { it.copy(message = "引擎启动异常：${e.message}") }
            }
        }
    }

    /** 分析当前局面（流式，实时刷新候选选点与胜率） */
    fun analyzeCurrent() {
        val c = client ?: run { _ui.update { it.copy(message = "请先启动引擎") }; return }
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _ui.update { it.copy(analyzing = true, message = "分析中…") }
            try {
                resyncEngine(c)
                val turn = state.turn()
                val cmd = GtpCommand.analyze(intervalCentis = 10, kata = true)
                c.streamAnalyze(cmd).collect { line ->
                    val result = AnalysisParser.parse(line, turn)
                    if (result != null) updateAnalysis(result)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(message = "分析失败：${e.message}") }
            } finally {
                _ui.update { it.copy(analyzing = false) }
            }
        }
    }

    /** 停止当前分析 */
    fun stopAnalysis() {
        analyzeJob?.cancel()
        _ui.update { it.copy(analyzing = false) }
    }

    /** 批量分析整盘，生成胜率曲线。每局面取一次快照即前进。 */
    fun analyzeWholeGame() {
        val c = client ?: run { _ui.update { it.copy(message = "请先启动引擎") }; return }
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _ui.update { it.copy(analyzing = true, message = "整盘分析中…", batchProgress = 0f) }
            try {
                val total = state.totalMoves().coerceAtLeast(1)
                val history = LinkedHashMap<Int, Float>(_ui.value.winrateHistory)
                for (i in 0..total) {
                    state.gotoIndex(i)
                    resyncEngine(c)
                    val turn = state.turn()
                    val result = queryOnce(c, turn)
                    if (result != null) {
                        // 统一记为黑方胜率入库，便于曲线一致
                        val black = if (turn == Stone.BLACK) result.rootWinrate else 100f - result.rootWinrate
                        history[i] = black
                    }
                    _ui.update { it.copy(winrateHistory = history, batchProgress = i.toFloat() / total) }
                    refreshBoard()
                }
                _ui.update { it.copy(message = "整盘分析完成", batchProgress = 1f) }
            } catch (e: Exception) {
                _ui.update { it.copy(message = "批量分析失败：${e.message}") }
            } finally {
                _ui.update { it.copy(analyzing = false) }
            }
        }
    }

    /** 取一次分析快照：收集流直到访问数达标或最多读取若干行 */
    private suspend fun queryOnce(c: GtpClient, turn: Stone): AnalysisResult? {
        val cmd = GtpCommand.analyze(intervalCentis = 5, kata = true)
        var latest: AnalysisResult? = null
        try {
            c.streamAnalyze(cmd).collect { line ->
                val r = AnalysisParser.parse(line, turn) ?: return@collect
                latest = r
                if (r.visits >= 80) return@collect // 达到足够访问数即可
            }
        } catch (_: Exception) { /* 取已得到的最新结果 */ }
        return latest
    }

    private fun updateAnalysis(result: AnalysisResult) {
        val idx = state.currentIndex()
        val black = if (state.turn() == Stone.BLACK) result.rootWinrate else 100f - result.rootWinrate
        val history = LinkedHashMap(_ui.value.winrateHistory)
        history[idx] = black
        val board = BoardRenderModel.from(
            board = state.board(),
            lastMove = state.lastMovePoint(),
            analysis = result,
            showCoords = _ui.value.showCoords,
            interactive = false,
            showSuggestions = true,
        )
        _ui.update { it.copy(board = board, analysis = result, winrate = result.rootWinrate, winrateHistory = history) }
    }

    private fun refreshBoard() {
        val board = BoardRenderModel.from(
            board = state.board(),
            lastMove = state.lastMovePoint(),
            analysis = null,
            showCoords = _ui.value.showCoords,
            interactive = false,
            showSuggestions = true,
        )
        _ui.update { it.copy(
            board = board,
            currentIndex = state.currentIndex(),
            totalMoves = state.totalMoves(),
            winrate = _ui.value.winrateHistory[state.currentIndex()],
        ) }
    }

    private suspend fun resyncEngine(c: GtpClient) {
        runCatching {
            c.boardsize(state.size)
            c.komi(state.komi)
            c.clear_board()
            state.setupStones().forEach { (p, s) -> c.play(s, p, state.size) }
            for (rm in state.movesList().take(state.currentIndex())) {
                val stone = rm.stone ?: continue
                when (val m = rm.move) {
                    is Move.Play -> c.play(stone, m.point, state.size)
                    is Move.Pass -> c.play(stone, -1, state.size)
                    Move.Resign -> Unit
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        client?.close()
    }

    /** 当前局面的胜率曲线点序列（黑方胜率，0-100） */
    fun winrateSeries(): List<Float> {
        val total = state.totalMoves()
        if (total == 0) return emptyList()
        val history = _ui.value.winrateHistory
        val result = ArrayList<Float>(total + 1)
        for (i in 0..total) result.add(history[i] ?: 50f)
        return result
    }
}
