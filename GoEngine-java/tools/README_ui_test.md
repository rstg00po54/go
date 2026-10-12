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


## RK3588 自动测试报告

测试成功、失败、或 Ctrl+C 中断后，脚本会通过你本机已有的 Git 认证，把报告提交到独立的 `ui-test-results` 分支，不改动当前代码分支或未提交的工作区。

结果目录：https://github.com/rstg00po54/go/tree/ui-test-results/runs

**注意：GitHub 仓库是公开的，按你的要求，这个版本不再对诊断信息脱敏。** 每次上传原始测试报告及故障时的截图、Android UI XML、日志（单个文件大于 12MB 时保留本地）。文件可能包含设备标识、界面文字和日志中的其他信息，不适合有私人数据的设备。

如不希望上传可加 `--no-upload`。网络或 Git 权限失败不会删除本地报告。自动上传后可以直接在聊天里说「看看刚才最新测试结果」。

## 开始对局按钮

`MainActivity.showNewGameDialog()` 实际从 `dialog_new_game.xml` 创建对话框，并为 `R.id.startGameButton` 绑定了点击事件。本脚本首先尝试根据 UIAutomator 获取按钮；若设备 UI 树不包含该按钮，则利用「棋力等级」控件作为锚点，按照布局中最后一行的高度、12dp 顶部间距和 46dp 按钮高度计算真实位置点击。

因此这一按钮不再依赖模拟器 UIAutomator 是否导出 Button 节点，也无需重新编译 APK。不过仍需要在设备上跑一次验证；若失败，会上传新鲜的失败时 XML 和截图以供诊断。

## 速度与中断

UIAutomator 导出界面树在 RK3588 上仍有固定的数秒延迟。脚本会尽可能复用未变化的 UI 数据，并合并设备端命令，避免每一步无意义等待。Ctrl+C 会保存 `INTERRUPTED` 状态的测试报告，不打印一长串 traceback。
