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

/**
 * KataGo engine wrapper that handles communication with the native KataGo process
 * via GTP (Go Text Protocol)
 * 
 * Based on reverse-engineering of Baduk AI's Python implementation.
 * Key insight: KataGo needs proper LD_LIBRARY_PATH, HOME, and ADSP_LIBRARY_PATH.
 * The process is spawned directly (not via shell) with proper environment.
 * 
 * PATCHED for com.badukai package name - uses libkatago.so with:
 * - Path check patched from /data/data/net.kir.baduk_ai to /data/data/com.badukai
 * - memcmp length patched from 27 to 22 bytes
 */
class KataGoEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "KataGoEngine"
        private const val BINARY_NAME = "libkatago.so"  // Patched KataGo binary
        private const val CONFIG_NAME = "gtp_static.cfg"  // KataGo config file
    }

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    private val _lastResponse = MutableStateFlow("")
    val lastResponse: StateFlow<String> = _lastResponse

    private var currentModel: String = ""
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var errorReader: BufferedReader? = null
    private var readerJob: Job? = null
    private var errorReaderJob: Job? = null
    private val responseQueue = LinkedBlockingQueue<String>()
    private val isRunning = AtomicBoolean(false)

    /**
     * Available models that can be used with KataGo
     * KataGo requires .bin.gz format for model weights
     * Note: Android build decompresses .gz files, so use .bin in code
     * 
     * Model strengths (approximate Elo ratings from katagotraining.org):
     * - HUMAN (10b): ~11,500 Elo - Fast, suitable for casual play
     * - SUPERHUMAN (18b): ~13,600 Elo - Strong, balanced speed/strength
     * - GODLIKE (28b): ~14,100 Elo - Strongest available, slower
     */
    enum class Model(val displayName: String, val fileName: String, val description: String) {
        HUMAN("Human", "10b.bin", "Approachable AI opponent, fast responses"),
        SUPERHUMAN("Superhuman", "18b.bin", "Very strong AI, balanced performance"),
        GODLIKE("Godlike", "28b.bin", "Ultimate strength, may be slower on some devices")
    }

    /**
     * Start the KataGo engine - PATCHED BINARY MODE
     * 
     * Uses a patched binary that checks for /data/data/com.badukai
     * Key requirements:
     * 1. Working directory must be under /data/data/com.badukai (not /data/user/0/)
     * 2. Model files must be in files/app/
     * 3. Config file (gtp_static.cfg) is required for KataGo
     */
    suspend fun start(model: Model = Model.HUMAN): Boolean = withContext(Dispatchers.IO) {
        if (isRunning.get()) {
            Log.w(TAG, "Engine already running")
            return@withContext true
        }

        Log.i(TAG, "=== PATCHED KATAGO ENGINE ===")

        try {
            // 1. Setup Paths using /data/data/ format (required by patched binary)
            // The binary checks that /proc/self/cwd starts with /data/data/com.badukai
            val dataDataPath = "/data/data/${context.packageName}"
            val filesDir = File(dataDataPath, "files")
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            
            // Create hexagon directory - must use /data/data/ format!
            val hexagonDir = File(filesDir, "hexagon")
            if (!hexagonDir.exists()) {
                hexagonDir.mkdirs()
                Log.i(TAG, "Created hexagon directory: ${hexagonDir.absolutePath}")
            }
            
            // 2. Copy patched KataGo binary from assets if needed
            val binaryFile = File(filesDir, BINARY_NAME)
            if (!binaryFile.exists() || shouldUpdateBinary(binaryFile)) {
                copyAssetToFile(BINARY_NAME, binaryFile)
                binaryFile.setExecutable(true)
                Log.i(TAG, "Installed patched KataGo binary")
            }
            
            // 3. Copy config file from assets
            val configFile = File(filesDir, CONFIG_NAME)
            if (!configFile.exists()) {
                copyAssetToFile(CONFIG_NAME, configFile)
                Log.i(TAG, "Copied config file: ${configFile.absolutePath}")
            }
            
            // 4. Prepare Model files - Python puts them in files/app/
            val appDir = File(filesDir, "app")
            if (!appDir.exists()) appDir.mkdirs()
            
            val modelFile = File(appDir, model.fileName)
            if (!modelFile.exists()) {
                copyAssetToFile("models/${model.fileName}", modelFile)
            }
            
            Log.i(TAG, "Model: ${modelFile.absolutePath} (exists=${modelFile.exists()}, size=${modelFile.length()})")
            Log.i(TAG, "Config: ${configFile.absolutePath} (exists=${configFile.exists()})")
            Log.i(TAG, "Binary: ${binaryFile.absolutePath} (exists=${binaryFile.exists()})")

            // 5. Build command using linker64 to execute from files directory
            // Android doesn't allow direct execution from app data directories (W^X policy)
            // But we can use linker64 to load and run the binary
            //
            // KataGo command format: gtp -model MODEL -config CONFIG
            val command = listOf(
                "/system/bin/linker64",
                binaryFile.absolutePath,
                "gtp",  // KataGo subcommand
                "-model", modelFile.absolutePath,
                "-config", configFile.absolutePath
            )
            Log.i(TAG, "Command: ${command.joinToString(" ")}")

            // 5. Build ProcessBuilder with environment
            val builder = ProcessBuilder(command)
            
            // CRITICAL: Working directory must be under /data/data/com.badukai
            // The binary reads /proc/self/cwd and checks it starts with /data/data/com.badukai
            builder.directory(hexagonDir)
            
            val env = builder.environment()
            env["LD_LIBRARY_PATH"] = "$nativeLibDir:${hexagonDir.absolutePath}:/vendor/lib64:/system/vendor/lib64"
            env["ADSP_LIBRARY_PATH"] = "$nativeLibDir;${hexagonDir.absolutePath};/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp"
            env["HOME"] = filesDir.absolutePath
            
            Log.i(TAG, "Environment:")
            Log.i(TAG, "  LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]}")
            Log.i(TAG, "  ADSP_LIBRARY_PATH=${env["ADSP_LIBRARY_PATH"]}")
            Log.i(TAG, "  HOME=${env["HOME"]}")
            Log.i(TAG, "  Working dir=${hexagonDir.absolutePath}")

            // 6. Launch the process
            Log.i(TAG, "Launching process...")
            process = builder.start()
            
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            errorReader = BufferedReader(InputStreamReader(process!!.errorStream))

            // 7. Wait a moment and check if process is still alive
            delay(2000)  // Allow time for neural network initialization
            
            val alive = process?.isAlive ?: false
            val exitCode = try { process?.exitValue() } catch (e: IllegalThreadStateException) { null }
            Log.i(TAG, "Process alive: $alive, exitCode: $exitCode")
            
            if (!alive) {
                // Process died immediately - read any error output
                val error = errorReader?.readText() ?: ""
                val output = reader?.readText() ?: ""
                Log.e(TAG, "Process died immediately!")
                Log.e(TAG, "Stderr: $error")
                Log.e(TAG, "Stdout: $output")
                return@withContext false
            }

            // 8. Start the reader jobs
            isRunning.set(true)
            currentModel = model.fileName
            _isReady.value = true
            startReaderJob()
            startErrorReaderJob()
            
            Log.i(TAG, "=== ENGINE STARTED SUCCESSFULLY ===")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start engine", e)
            return@withContext false
        }
    }
    
    /**
     * Copy hexagon skeleton files from assets if available
     */
    private fun copyHexagonSkeletons(hexagonDir: File) {
        val hexagonFiles = listOf(
            "libQnnHtpV68Skel.so",
            "libQnnHtpV69Skel.so", 
            "libQnnHtpV73Skel.so",
            "libQnnHtpV75Skel.so",
            "libQnnHtpV79Skel.so",
            "libQnnHtpV81Skel.so",
            "libQnnDspV66Skel.so",
            "libCalculator_skel.so"
        )
        
        for (fileName in hexagonFiles) {
            try {
                val destFile = File(hexagonDir, fileName)
                if (!destFile.exists()) {
                    context.assets.open("hexagon/$fileName").use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Copied hexagon skeleton: $fileName")
                }
            } catch (e: Exception) {
                // Hexagon files are optional - only needed for DSP acceleration
                Log.d(TAG, "Hexagon skeleton not available: $fileName")
            }
        }
    }

    private fun startReaderJob() {
        readerJob = scope.launch {
            try {
                val buffer = StringBuilder()
                Log.i(TAG, "Reader job started, waiting for output...")
                
                while (isActive && isRunning.get()) {
                    val line = withContext(Dispatchers.IO) {
                        try {
                            reader?.readLine()
                        } catch (e: IOException) {
                            Log.e(TAG, "IOException reading: ${e.message}")
                            null
                        }
                    }
                    
                    if (line == null) {
                        // Check process state when stream closes
                        val alive = process?.isAlive
                        val exit = try { process?.exitValue() } catch (e: Exception) { -999 }
                        Log.i(TAG, "KataGo stdout stream closed (alive=$alive, exit=$exit)")
                        break
                    }
                    
                    Log.d(TAG, "KataGo stdout: $line")
                    buffer.append(line).append("\n")
                    
                    // GTP responses end with an empty line after the response
                    if (line.isEmpty() && buffer.isNotEmpty()) {
                        val response = buffer.toString()
                        buffer.clear()
                        responseQueue.offer(response)
                        _lastResponse.value = response
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stdout reader job error", e)
            }
        }
    }
    
    private fun startErrorReaderJob() {
        errorReaderJob = scope.launch {
            try {
                while (isActive && isRunning.get()) {
                    val line = withContext(Dispatchers.IO) {
                        try {
                            errorReader?.readLine()
                        } catch (e: IOException) {
                            null
                        }
                    }
                    
                    if (line == null) {
                        Log.i(TAG, "KataGo stderr stream closed")
                        break
                    }
                    
                    Log.e(TAG, "KataGo stderr: $line")
                    
                    // Log stderr for debugging but don't add to response queue
                    Log.w(TAG, "KataGo stderr: $line")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stderr reader job error", e)
            }
        }
    }

    /**
     * Stop the KataGo engine
     */
    fun stop() {
        Log.i(TAG, "Stopping KataGo...")
        
        isRunning.set(false)
        _isReady.value = false
        
        try {
            // Send quit command
            sendCommandSync("quit")
        } catch (e: Exception) {
            // Ignore
        }
        
        readerJob?.cancel()
        readerJob = null
        
        errorReaderJob?.cancel()
        errorReaderJob = null
        
        try {
            writer?.close()
        } catch (e: Exception) {}
        
        try {
            reader?.close()
        } catch (e: Exception) {}
        
        try {
            errorReader?.close()
        } catch (e: Exception) {}
        
        process?.let { p ->
            try {
                // Wait a bit for graceful shutdown
                if (!p.waitFor(1, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                }
            } catch (e: Exception) {
                p.destroyForcibly()
            }
        }
        
        process = null
        writer = null
        reader = null
        responseQueue.clear()
        
        Log.i(TAG, "KataGo stopped")
    }

    /**
     * Send a GTP command to KataGo
     */
    fun sendCommand(command: String): Boolean {
        return sendCommandSync(command)
    }
    
    private fun sendCommandSync(command: String): Boolean {
        if (!isRunning.get() && command != "quit") {
            Log.w(TAG, "Cannot send command, engine not running")
            return false
        }
        
        return try {
            Log.d(TAG, "Sending: $command")
            writer?.write(command)
            writer?.newLine()
            writer?.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command: $command", e)
            false
        }
    }

    /**
     * Wait for a response from KataGo
     */
    fun waitForResponse(timeoutMs: Int = 30000): String {
        return try {
            val response = responseQueue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            response ?: ""
        } catch (e: InterruptedException) {
            ""
        }
    }

    /**
     * Request a move from KataGo for the given color
     */
    suspend fun generateMove(color: String): String? = withContext(Dispatchers.IO) {
        try {
            // Clear any pending responses
            responseQueue.clear()
            
            sendCommand("genmove $color")
            val response = waitForResponse(60000) // 60 second timeout for thinking
            
            // Parse GTP response: "= D4\n\n" or "= pass\n\n" or "= resign\n\n"
            val move = parseGtpResponse(response)
            Log.i(TAG, "Generated move for $color: $move")
            move
        } catch (e: Exception) {
            Log.e(TAG, "Error generating move", e)
            null
        }
    }

    /**
     * Play a move on the board
     */
    suspend fun playMove(color: String, move: String): Boolean = withContext(Dispatchers.IO) {
        try {
            responseQueue.clear()
            sendCommand("play $color $move")
            val response = waitForResponse(5000)
            response.startsWith("=")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing move", e)
            false
        }
    }

    /**
     * Set the board size
     */
    suspend fun setBoardSize(size: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            responseQueue.clear()
            sendCommand("boardsize $size")
            val response = waitForResponse(5000)
            response.startsWith("=")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting board size", e)
            false
        }
    }

    /**
     * Clear the board for a new game
     */
    suspend fun clearBoard(): Boolean = withContext(Dispatchers.IO) {
        try {
            responseQueue.clear()
            sendCommand("clear_board")
            val response = waitForResponse(5000)
            response.startsWith("=")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing board", e)
            false
        }
    }

    /**
     * Set komi
     */
    suspend fun setKomi(komi: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            responseQueue.clear()
            sendCommand("komi $komi")
            val response = waitForResponse(5000)
            response.startsWith("=")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting komi", e)
            false
        }
    }

    /**
     * Undo the last move
     */
    suspend fun undo(): Boolean = withContext(Dispatchers.IO) {
        try {
            responseQueue.clear()
            sendCommand("undo")
            val response = waitForResponse(5000)
            response.startsWith("=")
        } catch (e: Exception) {
            Log.e(TAG, "Error undoing move", e)
            false
        }
    }

    /**
     * Get the final score
     */
    suspend fun getFinalScore(): String? = withContext(Dispatchers.IO) {
        try {
            responseQueue.clear()
            sendCommand("final_score")
            val response = waitForResponse(10000)
            parseGtpResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting final score", e)
            null
        }
    }

    private fun parseGtpResponse(response: String): String? {
        val trimmed = response.trim()
        return when {
            trimmed.startsWith("= ") -> trimmed.substring(2).trim().split("\n").firstOrNull()?.trim()
            trimmed.startsWith("=") -> trimmed.substring(1).trim().split("\n").firstOrNull()?.trim()
            else -> null
        }
    }

    private fun createConfigFile(file: File, logFilePath: String) {
        val config = """
            # KataGo Configuration for Badukai
            
            # Logging
            logAllGTPCommunication = true
            logSearchInfo = true
            logToStderr = false
            logFile = $logFilePath
            
            # Backend settings - CPU only (most compatible)
            useNNAPI = false
            
            # Rules (Japanese-style)
            koRule = SIMPLE
            scoringRule = AREA
            taxRule = NONE
            multiStoneSuicideLegal = false
            hasButton = false
            whiteHandicapBonus = N
            
            # Bot behavior
            allowResignation = true
            resignConsecTurns = 20
            resignMinScoreDifference = 40
            resignMinMovesPerBoardArea = 0.4
            
            # Ponder disabled
            ponderingEnabled = false
            lagBuffer = 1.0
            
            # Search settings
            numSearchThreads = 2
            nnCacheSizePowerOfTwo = 16
            resignThreshold = -0.9
            
            # Threading
            nnMutexPoolSizePowerOfTwo = 14
            numNNServerThreadsPerModel = 1
        """.trimIndent()

        file.writeText(config)
        Log.i(TAG, "Config file created: ${file.absolutePath}")
        Log.i(TAG, "Log file configured to: $logFilePath")
    }

    /**
     * Check if the binary should be updated (e.g., after app upgrade)
     */
    private fun shouldUpdateBinary(binaryFile: File): Boolean {
        // Compare size with asset - if different, update
        try {
            val assetSize = context.assets.open(BINARY_NAME).use { it.available() }
            return binaryFile.length() != assetSize.toLong()
        } catch (e: Exception) {
            return true
        }
    }

    private fun copyAssetToFile(assetPath: String, outFile: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.i(TAG, "Asset copied: $assetPath -> ${outFile.absolutePath}")
    }

    fun isRunning(): Boolean = isRunning.get()
}
