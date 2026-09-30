package com.badukai.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class KataGoEngine(private val context: Context) {
    companion object {
        private const val TAG = "KataGoEngine"
        private const val BINARY_ASSET = "engine/katago"
        private const val LIBCXX_ASSET = "engine/libc++_shared.so"
        private const val CONFIG_ASSET = "engine/default_gtp.cfg"
        private const val DEFAULT_MODEL_ASSET = "engine/10b.bin"
    }

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady
    private val _lastResponse = MutableStateFlow("")
    val lastResponse: StateFlow<String> = _lastResponse

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val responseQueue = LinkedBlockingQueue<String>()
    private val running = AtomicBoolean(false)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var errorReader: BufferedReader? = null
    private var readerJob: Job? = null
    private var errorReaderJob: Job? = null

    enum class Model(val displayName: String, val fileName: String, val description: String) {
        HUMAN("Human", "10b.bin", "Approachable AI opponent, fast responses"),
        SUPERHUMAN("Superhuman", "18b.bin", "Very strong AI, balanced performance"),
        GODLIKE("Godlike", "28b.bin", "Ultimate strength, may be slower on some devices")
    }

    suspend fun start(model: Model = Model.HUMAN): Boolean = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext true
        Log.i(TAG, "=== KOTLIN KATAGO ENGINE / RK3588 ===")
        try {
            val engineDir = File(context.filesDir, "engine").apply { mkdirs() }
            val binaryFile = File(engineDir, "katago")
            val libcxxFile = File(engineDir, "libc++_shared.so")
            val configFile = File(engineDir, "default_gtp.cfg")
            var modelFile = File(engineDir, model.fileName)

            copyAssetToFile(BINARY_ASSET, binaryFile)
            copyAssetToFile(LIBCXX_ASSET, libcxxFile)
            copyAssetToFile(CONFIG_ASSET, configFile)
            try {
                copyAssetToFile("engine/${model.fileName}", modelFile)
            } catch (e: IOException) {
                if (model == Model.HUMAN) throw e
                Log.w(TAG, "${model.fileName} not bundled, falling back to 10b.bin")
                modelFile = File(engineDir, "10b.bin")
                copyAssetToFile(DEFAULT_MODEL_ASSET, modelFile)
            }

            if (!binaryFile.setExecutable(true, false) && !binaryFile.canExecute()) throw IOException("Cannot make KataGo executable: $binaryFile")
            libcxxFile.setExecutable(false, false)

            val command = listOf(binaryFile.absolutePath, "gtp", "-model", modelFile.absolutePath, "-config", configFile.absolutePath)
            val builder = ProcessBuilder(command).directory(engineDir)
            val env = builder.environment()
            env["LD_LIBRARY_PATH"] = "${engineDir.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
            env.remove("ADSP_LIBRARY_PATH")
            env["HOME"] = context.filesDir.absolutePath

            Log.i(TAG, "Model: ${modelFile.absolutePath} size=${modelFile.length()}")
            Log.i(TAG, "Config: ${configFile.absolutePath}")
            Log.i(TAG, "Binary: ${binaryFile.absolutePath}")
            Log.i(TAG, "Command: ${command.joinToString(" ")}")

            process = builder.start()
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            errorReader = BufferedReader(InputStreamReader(process!!.errorStream))

            delay(2000)
            if (process?.isAlive != true) {
                Log.e(TAG, "KataGo process exited during startup")
                stop()
                return@withContext false
            }

            running.set(true)
            _isReady.value = true
            startReaderJob()
            startErrorReaderJob()
            Log.i(TAG, "=== ENGINE STARTED SUCCESSFULLY ===")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start engine", e)
            stop()
            false
        }
    }

    private fun startReaderJob() {
        readerJob = scope.launch {
            val buffer = StringBuilder()
            try {
                while (isActive && running.get()) {
                    val line = reader?.readLine() ?: break
                    Log.d(TAG, "KataGo stdout: $line")
                    buffer.append(line).append('\n')
                    if (line.isEmpty() && buffer.isNotEmpty()) {
                        val response = buffer.toString()
                        buffer.clear()
                        responseQueue.offer(response)
                        _lastResponse.value = response
                    }
                }
            } catch (e: IOException) {
                if (running.get()) Log.e(TAG, "Stdout reader failed", e)
            }
        }
    }

    private fun startErrorReaderJob() {
        errorReaderJob = scope.launch {
            try {
                while (isActive && running.get()) {
                    val line = errorReader?.readLine() ?: break
                    Log.w(TAG, "KataGo stderr: $line")
                }
            } catch (e: IOException) {
                if (running.get()) Log.e(TAG, "Stderr reader failed", e)
            }
        }
    }

    fun stop() {
        try { sendCommandSync("quit") } catch (_: Exception) {}
        running.set(false)
        _isReady.value = false
        readerJob?.cancel(); readerJob = null
        errorReaderJob?.cancel(); errorReaderJob = null
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { errorReader?.close() } catch (_: Exception) {}
        process?.let { p ->
            try { if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly() } catch (_: Exception) { p.destroyForcibly() }
        }
        process = null; writer = null; reader = null; errorReader = null
        responseQueue.clear()
        Log.i(TAG, "KataGo stopped")
    }

    fun sendCommand(command: String): Boolean = sendCommandSync(command)

    private fun sendCommandSync(command: String): Boolean {
        if (!running.get() && command != "quit") return false
        val w = writer ?: return false
        return try {
            Log.d(TAG, "Sending: $command")
            w.write(command); w.newLine(); w.flush(); true
        } catch (e: IOException) {
            Log.e(TAG, "Send failed: $command", e); false
        }
    }

    fun waitForResponse(timeoutMs: Int = 30000): String = try {
        responseQueue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: ""
    } catch (_: InterruptedException) { "" }

    suspend fun generateMove(color: String): String? = withContext(Dispatchers.IO) {
        responseQueue.clear()
        if (!sendCommandSync("genmove $color")) return@withContext null
        val move = parseGtpResponse(waitForResponse(60000))
        Log.i(TAG, "Generated move for $color: $move")
        move
    }

    suspend fun playMove(color: String, move: String): Boolean = simpleCommand("play $color $move", 5000)
    suspend fun setBoardSize(size: Int): Boolean = simpleCommand("boardsize $size", 5000)
    suspend fun clearBoard(): Boolean = simpleCommand("clear_board", 5000)
    suspend fun setKomi(komi: Float): Boolean = simpleCommand("komi $komi", 5000)
    suspend fun undo(): Boolean = simpleCommand("undo", 5000)

    suspend fun getFinalScore(): String? = withContext(Dispatchers.IO) {
        responseQueue.clear()
        if (!sendCommandSync("final_score")) return@withContext null
        parseGtpResponse(waitForResponse(10000))
    }

    private suspend fun simpleCommand(command: String, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        responseQueue.clear()
        if (!sendCommandSync(command)) return@withContext false
        waitForResponse(timeoutMs).startsWith("=")
    }

    private fun parseGtpResponse(response: String): String? {
        val trimmed = response.trim()
        if (!trimmed.startsWith("=")) return null
        val body = trimmed.substring(1).trim()
        return body.lineSequence().firstOrNull()?.trim()
    }

    private fun copyAssetToFile(assetPath: String, outFile: File) {
        outFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input -> FileOutputStream(outFile, false).use { output -> input.copyTo(output) } }
        Log.i(TAG, "Asset copied: $assetPath -> ${outFile.absolutePath}")
    }

    fun isRunning(): Boolean = running.get()
}
