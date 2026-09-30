package com.badukai.app.engine

/**
 * KataGo 引擎配置。
 *
 * @param executablePath 引擎可执行文件路径（空表示尚未配置，将回退到内置路径）
 * @param modelPath 神经网络模型文件路径（用户选取的权重文件）
 * @param configPath KataGo 配置文件路径（空表示使用内置默认配置）
 * @param threads 推理线程数
 * @param visits 单手最大搜索访问数（通过配置文件或 GTP 命令生效）
 * @param komi 贴目
 */
data class EngineConfig(
    val executablePath: String = "",
    val modelPath: String = "",
    val configPath: String = "",
    val threads: Int = 4,
    val visits: Int = 800,
    val komi: Float = 7.5f
) {
    /** 是否已具备对弈条件：引擎可执行文件与权重文件都已就位 */
    fun isReady(): Boolean = executablePath.isNotEmpty() && modelPath.isNotEmpty()
}
