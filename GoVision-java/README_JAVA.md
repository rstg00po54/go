# BadukAI Java/XML rewrite

这是从上传的 BadukAI Kotlin/Compose 工程重写出的 Java + XML 版本，不依赖 Kotlin/Compose。

当前包含：
- Java `MainActivity`
- XML 页面：对弈 / 分析 / 设置
- Java `BoardView extends View`，Canvas 绘制棋盘、棋子、推荐点、胜率、visits
- Java KataGo `EngineBootstrap` / `EngineManager` / `GtpClient`
- APK 内置 katago、libc++_shared.so、default_gtp.cfg
- 系统文件选择器选择模型并复制到 app 私有目录
- 引擎测试、启动、基础人机落子
- EngineManager 为进程内单例，避免各页面重复拉起多个 KataGo

为便于与原版共存，applicationId 使用 `com.badukai.java`，桌面名 `BadukAI Java`。

`分析`页目前使用演示推荐数据，视觉层已经是 Java Canvas；下一步可把 KataGo `kata-analyze` 的实时输出解析后直接喂给 `BoardView.setAnalysis()`。
