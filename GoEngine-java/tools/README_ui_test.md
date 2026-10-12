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


## 自动上传测试结果（默认开启）

测试成功、失败或按 Ctrl+C 中断后，脚本均先把完整测试结果保存在本地，再尝试通过本机已经配置好的 Git remote（默认 origin）自动上传一个**脱敏 JSON** 到独立分支 ui-test-results。它不会切换你本地的分支、提交未暂存文件或更改工作区，也不会把测试结果写进 rk3588-engine 代码分支。

报告页面：https://github.com/rstg00po54/go/tree/ui-test-results/runs

**仓库是公开的。** 上传内容仅包含测试状态、命令参数（不含设备序列号）、受限步骤记录、启动各阶段耗时、实际后端、经过精简的错误信息；完整 logcat、failed.xml、截图、设备序列号和任意栈信息都不会上传。注意：即使脱敏，也建议不要用个人隐私场景的 APP 作这套测试。

可禁用自动上传：

    python3 tools/test_app_ui.py -s 8719e18a71a2a66c --backends cpu --no-upload

如果你的 Git remote 不叫 origin：

    python3 tools/test_app_ui.py -s 8719e18a71a2a66c --upload-remote github

网络或权限问题会显示“自动上传失败”，本地 build/app_ui_test/ 下的结果仍完整保留。成功时终端会输出这次报告的具体 GitHub URL；你把 URL 发回来，或者直接说“看最新 UI 测试结果”即可。

## 速度与中断

- 旧实现每一步都会启动 uiautomator dump，获取一次 Android 无障碍界面树通常需要 1～3 秒。
- 现在只用一次 ADB 连接获取 XML，并复用同一帧未改变的 UI 数据；点击后清除缓存，等待动态状态会重新检查。
- 仍然可能需要数秒，特别是切换模型/等待 AI 思考。速度不是由 Python 运算造成的。
- Ctrl+C 会生成 status=INTERRUPTED 的本地 JSON，并尝试自动上传。若中断发生在 ADB 子进程中，也不再直接抛出未捕获的 Python traceback。
