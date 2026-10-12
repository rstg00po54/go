# RK3588 Android 围棋整局与终局数目自动测试

这是现有 `test_app_ui.py` 新增的 `--full-game` 模式；无需重新编译 APK。
脚本不会从主机直接向 KataGo 注入 GTP 落子，而是模拟触摸棋盘、监测 AI 真正落子、模拟停一手，直到 Android 上出现双方连续 pass 后真正执行 `finishByScore`。

## 首次运行（建议先单独测试 GPU 9×9）

在 GoEngine-java 目录执行：

    git pull origin rk3588-engine
    python3 tools/test_full_game.py
    python3 tools/test_app_ui_units.py
    python3 tools/test_app_ui.py -s 8719e18a71a2a66c --backends gpu --size 9 --full-game --game-max-actions 110 --settlement-timeout 180

GPU 通过后再尝试 `--backends cpu,gpu`，以比较不同引擎的结算时长。
整局自动对弈可能比已有每局 8 手的性能基准运行久得多；上限是每局最多 110 次玩家行动，不保证能在上限内自然终局。

## 测试内容

- 复用原有首页引擎就绪、CPU/GPU 启动确认与截图功能；
- 玩家黑棋正常下棋，脚本维护简单的棋盘模型（落子、提子、避免自杀和立即打劫）来避免无效交叉点；
- 监测 `handleAiMove` 的坐标/停一手，持续下棋。AI 停一手后，玩家立即停一手，以真实结束对局；
- 当棋盘没有适合落子的点时，玩家停一手，等待 AI 决定是继续还是也停一手；
- 只有出现 `finishByScore`，随后 `Chinese area score` 和终局结果的界面渲染，并有三个 KataGo 命令的实际日志，才算通过。

如果 AI 提前认输、测试期间崩溃、回棋/结算超时、最终没有数目日志，脚本会 FAIL 并保存故障日志；不会伪造一个完成的棋局。可以用 Ctrl+C 中断，仍生成中断报告。

## 结算耗时

| JSON 字段 | 含义 |
|---|---|
| `total_ms` | `finishByScore` → 最终数目界面 render |
| `final_score_ms` | 发送 `final_score` → 发送 `final_status_list dead` |
| `dead_stones_ms` | 发送 `final_status_list dead` → 发送 `kata-raw-nn 0` |
| `raw_nn_to_render_ms` | 发送 `kata-raw-nn 0` → 结果呈现 |
| `position_evaluation_ms` | APP 内部 Position evaluation 的 `elapsedMs`（如有） |
| `gtp_final_score` | `final_score` 的 GTP 返回值（日志中可见时） |

前三项阶段时间用 Android `logcat -v time` 的发送事件和下一命令发送事件相减，**属于各阶段的近似墙钟时间**，含 Java 串行处理和 GTP 开销，不是纯 GPU/CPU 推理时间。

每局输出 `full_game.json`（累计成功的棋局）、单局截图、最终原始 logcat，以及 GitHub `ui-test-results` 分支上的自动报告。上传沿用现有的**公开、未脱敏**报告方式，请仅在测试设备上运行；如不希望公开上传，加 `--no-upload`。

`--full-game` 不允许和 `--benchmark`、`--undo`、`--pass`、`--slow-ui` 同时使用，以免判定流程冲突。普通固定手数测试与性能基准保持不变。
