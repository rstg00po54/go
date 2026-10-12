# RK3588 KataGo 对局响应性能测试

独立入口：tools/bench_app_ai.py。复用现有 tools/test_app_ui.py，只改 Python，不需要重新编译 APK。

## 一次跑完 CPU → GPU → CPU

在 GoEngine-java 目录：

    git pull origin rk3588-engine
    python3 tools/test_bench_app_ai.py
    python3 tools/test_app_ui_units.py
    python3 tools/bench_app_ai.py -s 8719e18a71a2a66c --backends cpu,gpu,cpu --size 9 --moves 4

第一次建议每局 4 手验证全流程；稳定以后增大样本：

    python3 tools/bench_app_ai.py -s 8719e18a71a2a66c --backends cpu,gpu,cpu --size 9 --moves 8 --warmup-moves 1

可额外使用原 UI 工具的 --rounds 2、--no-upload、--slow-ui 等参数。性能模式扩展了可用落点，不会反复尝试本测试玩家已经下过的位置。

## 时间的严格含义

| 结果字段 | Android 日志区间 |
|---|---|
| tap_to_ai_ms | onBoardTap in 到 handleAiMove in |
| tap_to_ready_ms | onBoardTap in 到 render in, message=轮到你了 |
| ai_search_from_accept_ms | render in, message=AI 思考中 到 handleAiMove in |
| startup_ms | Saved new-game preference 到 Game backend requested...ready |
| rejected_taps | 没有进入 AI 思考中的棋盘点击，排除在统计外 |

这是安卓应用端到端响应延迟，包含落子同步、JNI/GTP、AI 搜索和调度，不是单次神经网络纯推理耗时或 OpenCL kernel 性能。开局模型初始化单独显示。

每局统计平均值、中位数、最近秩 P95、最大值和样本数，CPU 两轮与 GPU 一轮分别汇总。样本数较少时 P95 可能等于最大值。--warmup-moves N 可以每局忽略前 N 次有效回棋（仍然下棋）。

严格根据 MainActivity 的实际 CPU/Eigen JNI / GPU/OpenCL JNI ready 状态验收。如果 GPU 回退到 CPU、预期局数或有效回棋数不足，基准失败，不能误标 PASS。

## 输出

在 build/app_ui_test/日期/ 下生成 benchmark.json、benchmark_moves.csv、benchmark_summary.log、final_logcat.txt 和 report.json；自动上传到公开的 GitHub ui-test-results 分支。上传未经脱敏，仅在没有敏感数据的 RK3588 测试设备使用。

离线分析某份旧版 DEBUG 日志（无需连接设备）：

    python3 tools/bench_app_ai.py --analyze build/app_ui_test/某次目录/final_logcat.txt --backends cpu,gpu,cpu --size 9 --moves 2 --out old_benchmark.json

如果测试有异常，上传分支仍会保存失败时的日志和截图，方便直接复查。
