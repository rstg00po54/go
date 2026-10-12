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

## 新的黑棋保活策略与局面质量标记

旧脚本按固定坐标一直落子，容易把黑棋全部送光。现在使用确定性的
`go_play_strategy.py` 进行候选点评分：开局优先角部、后续优先连接和扩展，
当棋块只剩一口气时抢救，主动提掉对方危险棋块，回避自打吃、填自己眼位、
直接钻入已被对手包围的位置。它只负责选点，仍由 Android APP 决定是否接受落子；
不调用或修改 KataGo 引擎，也**不能保证一定战胜 AI**。

终局新增报告字段 `position_quality`：记录黑白实际剩余子数、
双方独立地盘、脚本棋盘跟踪与 APK 结算是否一致。
当任一方的存活棋子或地盘过少（9 路最低 3 子、2 个空点）时标为
`UNREPRESENTATIVE`；符合最低两方存活/地盘条件标为
`REPRESENTATIVE`。这只是复杂终局基准的**基本筛选条件**，
不保证真的存在复杂的死活判断。即使数目流程 PASS，一边倒仍单独标记
`UNREPRESENTATIVE`，不能拿它的耗时代表复杂终局。

## 测试内容

- 复用原有首页引擎就绪、CPU/GPU 启动确认与截图功能；
- 玩家黑棋正常下棋，脚本维护简单的棋盘模型（落子、提子、避免自杀和立即打劫）来避免无效交叉点；
- 监测 `handleAiMove` 的坐标/停一手，持续下棋。AI 停一手后，玩家立即停一手，以真实结束对局；
- 当棋盘没有适合落子的点时，玩家停一手，等待 AI 决定是继续还是也停一手；
- 当前试验版默认 `scoring_mode=fast_nn`：只有出现 `finishByScore`、一次成功的 `kata-raw-nn 0`、有效 `nnWhiteLead`、`Chinese area score` 和最终渲染，才算实验模式 PASS。启用原版/发生回退时走 `scoring_mode=full`，必须包含有效的 `final_score`、死子命令及数子日志才算 PASS。脚本不允许上一版只有死子搜索的 `fast` 模式冒充实验版通过。`fast_nn` 的 PASS **不代表估算目数与原版一致**。

如果 AI 提前认输、测试期间崩溃、回棋/结算超时、最终没有数目日志，脚本会 FAIL 并保存故障日志；不会伪造一个完成的棋局。可以用 Ctrl+C 中断，仍生成中断报告。

## 结算耗时

| JSON 字段 | 含义 |
|---|---|
| `total_ms` | `finishByScore` → 最终数目界面 render |
| `final_score_ms` | 仅 `full` 模式：`final_score` → `final_status_list dead`；实验 `fast_nn` 为 `null` |
| `dead_stones_ms` | 仅 `full` 模式：`final_status_list dead` → `kata-raw-nn 0`；`fast_nn` 为 `null` |
| `raw_nn_to_render_ms` | 发送 `kata-raw-nn 0` → 结果呈现 |
| `position_evaluation_ms` | APP 内部 Position evaluation 的 `elapsedMs`（如有） |
| `gtp_final_score` | `final_score` 的 GTP 返回值（上一版快速路径为 `null`） |
| `scoring_mode` | `fast_nn`（当前实验默认，单次 NN）、`full`（原版或回退）、`fast`（历史实验） |
| `time_to_first_result_ms` | 结算到首次可用结果：`fast_nn` 为结果显示时间，`full` 为 `final_score` 返回时间（如有） |
| `raw_white_lead` | `fast_nn` 模式里 KataGo 神经网络一次推理的预估白方领先目数 |

阶段时间使用 Android `logcat -v time` 的 GTP 命令发送事件及下一事件相减，**属于近似墙钟时间**，包含 Java 串行处理和 GTP 开销，不是纯 GPU/CPU 推理时间。本次实验默认只做单次原始 NN 推理、棋块级快速死子建议与中国数子（计分仅保留日志）；原版 `final_score` 需要点击终局「深度对照」按钮才能发起。详细操作见 `tools/README_fast_nn_endgame.md`。

每局输出 `full_game.json`（累计成功的棋局，含 `position_quality`）、单局截图、最终原始 logcat，以及 GitHub `ui-test-results` 分支上的自动报告。上传沿用现有的**公开、未脱敏**报告方式，请仅在测试设备上运行；如不希望公开上传，加 `--no-upload`。

`--full-game` 不允许和 `--benchmark`、`--undo`、`--pass`、`--slow-ui` 同时使用，以免判定流程冲突。普通固定手数测试与性能基准保持不变。
