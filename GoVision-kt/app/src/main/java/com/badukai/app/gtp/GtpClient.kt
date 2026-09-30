package com.badukai.app.gtp

import com.badukai.app.core.Stone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** 引擎工作状态：IDLE 空闲、THINKING 正在处理命令、STOPPED 已停止 */
enum class EngineState { IDLE, THINKING, STOPPED }

/** GTP 响应：[success] 为 true 表示 '=' 成功，[text] 为去掉状态头后的纯结果文本 */
data class GtpResponse(val success: Boolean, val text: String)

/**
 * 通过子进程启动外部 GTP 引擎（如 KataGo），用 stdin/stdout 文本协议通信。
 *
 * Android 注意：若 KataGo 链接了 libc++_shared.so，必须通过 [env] 参数传入
 *   LD_LIBRARY_PATH=<同目录>，否则启动即崩 "library libc++_shared.so not found"
 */
class GtpClient(
    executablePath: String,
    workingDir: File,
    args: List<String>,
    env: Map<String, String> = emptyMap()
) {
    private val process: Process = try {
        val cmd = ArrayList<String>().apply {
            add(executablePath)
            addAll(args)
        }
        val pb = ProcessBuilder(cmd)
            .directory(workingDir)
            .redirectErrorStream(false)
        if (env.isNotEmpty()) {
            val pe = pb.environment()
            env.forEach { (k, v) ->
                // 对 PATH/LD_LIBRARY_PATH 这类变量，优先追加用户值而不是覆盖
                when (k) {
                    "LD_LIBRARY_PATH" -> {
                        val existing = pe[k].orEmpty()
                        pe[k] = if (existing.isEmpty()) v else "$v:$existing"
                    }
                    else -> pe[k] = v
                }
            }
        }
        pb.start()
    } catch (e: IOException) {
        throw IOException("无法启动引擎进程: ${e.message}", e)
    }

    private val reader: BufferedReader =
        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))

    private val mutex = Mutex()
    private val _state = MutableStateFlow(EngineState.IDLE)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 后台持续消费 stderr，防止引擎因 stderr 缓冲区写满而阻塞
    private val stderrJob: Job = scope.launch {
        val err = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))
        try {
            while (isActive) {
                val line = err.readLine() ?: break
            }
        } catch (_: IOException) {
        }
    }

    suspend fun send(command: String): GtpResponse = mutex.withLock {
        if (_state.value == EngineState.STOPPED) {
            throw IllegalStateException("引擎已停止，无法发送命令")
        }
        _state.value = EngineState.THINKING
        try {
            withContext(Dispatchers.IO) {
                writer.write(command)
                writer.newLine()
                writer.flush()
            }
            readResponse()
        } finally {
            if (_state.value != EngineState.STOPPED) {
                _state.value = EngineState.IDLE
            }
        }
    }

    suspend fun <T> send(command: String, parser: (String) -> T): T {
        val resp = send(command)
        if (!resp.success) {
            throw IOException("GTP 命令失败 [$command]: ${resp.text}")
        }
        return parser(resp.text)
    }

    private suspend fun readResponse(): GtpResponse = withContext(Dispatchers.IO) {
        val content = StringBuilder()
        var success = false
        var headerParsed = false

        while (true) {
            val line = reader.readLine() ?: throw IOException("引擎输出流意外关闭")
            if (!headerParsed) {
                if (line.isBlank()) continue
                var idx = 0
                while (idx < line.length && line[idx].isWhitespace()) idx++
                if (idx < line.length && line[idx].isDigit()) {
                    while (idx < line.length && line[idx].isDigit()) idx++
                    while (idx < line.length && line[idx].isWhitespace()) idx++
                }
                if (idx < line.length && (line[idx] == '=' || line[idx] == '?')) {
                    success = line[idx] == '='
                    idx++
                    if (idx < line.length && line[idx] == ' ') idx++
                    val first = line.substring(idx)
                    if (first.isNotEmpty()) content.append(first)
                } else {
                    success = false
                    content.append(line.trim())
                }
                headerParsed = true
                continue
            }
            if (line.isEmpty()) break
            if (content.isNotEmpty()) content.append('\n')
            content.append(line)
        }

        GtpResponse(success = success, text = content.toString())
    }

    fun streamAnalyze(command: String): Flow<String> = flow {
        mutex.withLock {
            if (_state.value == EngineState.STOPPED) {
                throw IllegalStateException("引擎已停止，无法发送命令")
            }
            _state.value = EngineState.THINKING
            try {
                withContext(Dispatchers.IO) {
                    writer.write(command)
                    writer.newLine()
                    writer.flush()
                }
                while (true) {
                    val line = withContext(Dispatchers.IO) {
                        runCatching { reader.readLine() }.getOrNull()
                    } ?: break
                    if (line.isEmpty()) break
                    emit(line)
                }
            } finally {
                runCatching {
                    writer.write("\n"); writer.flush()
                }
                withContext(Dispatchers.IO) {
                    while (true) {
                        val drain = runCatching { reader.readLine() }.getOrNull() ?: break
                        if (drain.isEmpty()) break
                    }
                }
                if (_state.value != EngineState.STOPPED) _state.value = EngineState.IDLE
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun genmove(color: Stone, size: Int): String {
        val resp = send(GtpCommand.genmove(color))
        if (!resp.success) throw IOException("genmove 失败: ${resp.text}")
        return resp.text.trim()
    }

    suspend fun play(color: Stone, point: Int, size: Int): Boolean =
        send(GtpCommand.play(color, point, size)).success

    suspend fun undo(): Boolean = send(GtpCommand.undo()).success

    suspend fun boardsize(n: Int): Boolean = send(GtpCommand.boardsize(n)).success

    suspend fun clear_board(): Boolean = send(GtpCommand.clear_board()).success

    suspend fun komi(k: Float): Boolean = send(GtpCommand.komi(k)).success

    suspend fun time_settings(mainTime: Int, byoYomiTime: Int, byoYomiStones: Int): Boolean =
        send(GtpCommand.time_settings(mainTime, byoYomiTime, byoYomiStones)).success

    suspend fun time_left(color: Stone, time: Int, stones: Int): Boolean =
        send(GtpCommand.time_left(color, time, stones)).success

    fun close() {
        _state.value = EngineState.STOPPED
        stderrJob.cancel()
        runCatching { writer.close() }
        runCatching { reader.close() }
        runCatching { process.destroy() }
        scope.cancel()
    }
}
