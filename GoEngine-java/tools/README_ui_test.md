# KataGo Android APP 一键 UI 回归测试

脚本只使用 Python 3.8+ 和系统 ADB，不依赖 Appium 或额外 Python 包。

## 一键运行

在 GoEngine-java 目录执行：

    python3 tools/test_app_ui.py -s 8719e18a71a2a66c --backends cpu,gpu,cpu --size 9 --moves 2 --undo

只测 CPU：

    python3 tools/test_app_ui.py -s 8719e18a71a2a66c --backends cpu --moves 1

连续切换 CPU/GPU 10 轮：

    python3 tools/test_app_ui.py -s 8719e18a71a2a66c --backends cpu,gpu --rounds 10 --moves 1

还支持 --pass（停一手）、--install APK路径（先安装）、--think-timeout 秒数 等选项。

## 执行流程

1. 通过 ADB 启动 com.badukai.java，等待首页 AI 就绪。
2. 打开 AI 训练，设置棋盘路数、执黑和 CPU/Eigen 或 GPU/OpenCL。
3. 确认实际运行后端；请求 GPU 却回退 CPU 会直接报错。
4. 从 boardView 的 Android UI bounds 与 GoBoardView 网格计算落点，自动下棋并等待 AI 回棋。
5. 可选悔棋、停一手；检查正常对弈没有启动 :katago_gpu 独立进程。
6. 保存截图、UI XML、logcat、启动耗时摘要和 report.json 到 build/app_ui_test/日期时间/。

## 前提与限制

- 手机已解锁，ADB 调试已授权，APP 已安装。
- GPU 测试需要 APK 打包 libkatago_gpu.so。
- 请先在 APP 内完成 Human SL 模型下载。脚本不会自动下载，也不会清除应用数据。
- 同时连接 USB 和无线 ADB 时，用 -s 指定设备。
- 尚未在实机上做端到端验证；设备 UI 树或启动遮罩不同可能导致脚本失败。失败时保留截图和日志供排查。
