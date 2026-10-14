# KataGo 功能清单与 Android 项目集成规划

> 项目：`go/GoEngine-java`  
> 整理日期：2026-10-14  
> 参考版本：KataGo v1.18.2（CUDA 优化版）；非 NVIDIA CUDA 后端请重点参考 v1.18.1。  
> 文档定位：**KataGo 原生能力索引 + Android 功能开发待办**，不是“以下功能均已在 APK 中实现”的声明。

## 0. 当前工程基线（重要）

以本仓库 `rk3588-engine` 分支的 `GoEngine-java/tools/README_smart_referee.md`（2026-10-14）为准：

- Android Java/XML 界面，正常对弈、CPU/GPU 选择、胜率/估分逻辑保留；现有文档说明使用 9/13/19 路对弈。
- **智能裁判当前仅调用一次 `kata-raw-nn 0`**，显示 NN 预测的领先方、目数及胜率；本地简单劫禁和单气棋块检查只提供风险提示。
- 智能裁判不进行 MCTS，不模拟 PASS，不调用 `final_score` / `final_status_list` 强行裁判，不自动落子或宣布正式结果。
- 双方停棋后的中国规则数子和人工确认死子流程单独保留，属于正式计分流程。
- 以前做过的 PASS 对照、Benson/Pass-Alive 性能实验只是历史测试，**不能写成当前智能裁判已启用的功能**。

下文使用：**原生** = KataGo 自带；**已接入** = 当前分支文档明确说明；**待集成** = 需要 App/JNI/GTP/UI 开发；**实验** = 需测试精度或设备支持。技术上“原生支持”不等于当前 Android APK 已暴露按钮。

## 1. AI 对弈与搜索

| 能力 | KataGo 支持方式 | Android 计划 |
|---|---|---|
| AI 自动落子 | GTP `genmove`；神经网络 + MCTS | 已接入对弈 |
| CPU / GPU 运行 | 构建时选不同推理后端、加载相应模型 | 已有切换；继续优化初始化与切换 |
| 调整难度 | visits/playouts、思考时间、搜索参数、模型、温度等 | 已有难度基础；完善分级 |
| 人类棋风 | Human SL 专用模型（与主对弈模型区分） | 待集成 |
| 自我对弈 | `selfplay`、`match` 等 | 训练/测试用 |
| AI 认输与 Pass | 搜索及对应配置、游戏规则 | 已有基本对弈流程；测试终局 |
| 棋盘大小 | 普通 7–19 路及矩形支持依模型而定；特殊尺寸需验证训练分布 | 9/11/13/15/19 分别测试 |
| 让子/贴目 | GTP 让子命令、`komi` | 可扩充 |
| 时间控制 | GTP 时间命令 | 待集成 |
| 搜索限制/重点候选手 | 搜索配置、分析参数 | 待集成 |
| 后台思考/停止搜索 | pondering、`stop`、取消式命令 | 待集成 |

**注意**：低 visits 不等于稳定的“几级棋力”。Human SL 需要独立模型；9×9 最好选专门适配的小棋盘模型。

## 2. 棋盘与规则引擎

- 合法落子、禁入点、提子、连通棋块、气数、打吃、自杀规则。
- 劫与同形禁着：简单劫、位置/局面超级劫等按规则配置处理。
- Pass、认输、对局阶段、撤销/重放、让子、贴目。
- 中国（数子）、日本/韩国（数目）、AGA、新西兰、Tromp-Taylor 等规则配置。特殊争议局面应以适用规则和人工确认作最终裁判。
- 初始局面设置 `set_position`、棋谱读取 `loadsgf`、导出 `printsgf`，以及非方形棋盘 `rectangular_boardsize`。
- `Board` / `BoardHistory` 提供棋盘局面、历史及规则计算能力；**底层 C++ 函数不等于直接可用的 GTP 命令**。

## 3. 原始神经网络能力（无需 MCTS）

GTP：`kata-raw-nn 0`（选择对称变换 0）；还可以选择其他 symmetry 或 `all`。

| 字段 | 用途 | 注意 |
|---|---|---|
| `whiteWin` / `whiteLoss` | 白赢/黑赢概率 | **预测**，非正式赛果 |
| `noResult` | 无结果局面的预测概率 | 特殊劫争相关 |
| `whiteLead` | 预测白方领先目数 | 当前快速估分核心 |
| `whiteScoreSelfplay` | 自对弈预测平均分差 | 与 `whiteLead` 不是同一指标 |
| `whiteScoreSelfplaySq` | 预测目数二阶矩 | 可推导离散程度 |
| `policy` | 各落点概率分布 | 可做落点建议/热力图 |
| `policyPass` | 当前玩家选择 Pass 的概率 | **不等于棋局已结束** |
| `whiteOwnership` | 每个交叉点最终归属预测 | 可画地盘热力图 |
| `varTimeLeft` | 胜负不确定性时间尺度的网络预测 | **不是还要下多少手结束** |
| `shorttermWinlossError` / `shorttermScoreError` | 短期预测误差估计 | 字段可用性应以当前模型输出为准 |

解析器不要依赖字段固定顺序或固定空白字符；模型和 KataGo 版本可能增加字段。

Human SL 模型可用 `kata-raw-human-nn`，但需启动时额外提供 human 模型，其输出字段与普通 NN 不完全相同。

## 4. 局面分析（需要 MCTS 或应用层处理的部分）

| 功能 | 数据来源 | App 工作 |
|---|---|---|
| 胜率曲线 | NN / MCTS | 保存每手结果，画曲线 |
| 目数趋势 | `whiteLead` / 分析输出 | 按手保存并展示 |
| 候选落点 | `kata-analyze` / JSON analysis | 展示 top N |
| 变化图（PV） | 搜索输出 | 棋盘预览变化分支 |
| 地盘热力图 | ownership | 绘制黑白归属 |
| 失误、恶手、漏着 | 对比前后分析值 | **App 自行定义标签和阈值** |
| 每手损失目数 | 比较最佳手与实战手的评估 | 统一同一视角和规则 |
| 棋谱自动复盘 | SGF/手顺 + 批量分析 | UI 和任务调度 |
| 实时分析 | 流式 GTP 或 analysis engine | 异步、取消、刷新节流 |
| 多局批量分析 | JSON analysis engine | 大批量时 GPU batching 可加速 |
| 死活训练辅助 | 规则检查 + AI 分析 | 题库、答案树、难度体系由 App 实现 |

NN 一次推理速度快；MCTS 更深入但通常更耗时。实时 UI 应避免在主线程阻塞。

## 5. 终局、计分、死活：要严格区分

### 5.1 原生接口

| 接口 | 实际作用 | 限制 |
|---|---|---|
| `BoardHistory::endGameIfAllPassAlive(board)` | 若全盘都能被判为确定的 Pass-Alive 区域，则尝试宣告结束 | **充分而非必要条件**；失败不能证明未下完 |
| `BoardHistory::endAndScoreGameNow(board)` | 对当前局面立即计分 | 强制计分 ≠ 正常终局 |
| `BoardHistory::getAreaNow(board, area)` | 按规则计算当前区域归属 | 不能替代神经网络死活判断 |
| `BoardHistory::passWouldEndGame(...)` | 判断该 Pass 是否会结束对局 | 依规则与阶段 |
| `BoardHistory::passWouldEndPhase(...)` / `isFinalPhase()` | 对局阶段判断 | 不等于“棋盘所有官子已经收完” |
| `BoardHistory::isGameFinished` / `isScored` | 棋局是否结束/已计分状态 | 读取 `BoardHistory` 状态 |
| `BoardHistory::finalWhiteMinusBlackScore` | 规则正式计分的白减黑分差 | 需有可靠终局/计分前提 |
| `BoardHistory::setWinnerByResignation(...)` | 记录认输胜者 | 不是目数结算 |
| GTP `final_score` | 报告引擎支持的终局分数 | 不建议对中盘局面当成正式结果 |
| GTP `final_status_list` | 指定状态查询棋子 | 不等于一键解决全部生死争议 |

**`endGameIfAllPassAlive` 不是 GTP 命令**，使用它须在 JNI/native 中接触 `BoardHistory`。如果试验时会修改状态，先复制 history，在副本中判定，**不得修改正在对局的正式状态**。

### 5.2 建议保留两条清晰路径

**A. 当前已实现的「智能裁判」**：一次 `kata-raw-nn 0`，展示预测胜率/目数和风险提示，不要求双方 Pass；不宣告官方终局。

**B. 未来可单独开发的「终局检测增强」**：
1. 复制当前棋盘和规则历史，先尝试 Pass-Alive 严格检测。
2. 仅当规则条件成立才标记“可以确定结束”；未通过只能显示“无法确定”，不要一律判“没下完”。
3. 再综合 NN ownership、双方 Pass 倾向、未收官子、未解决劫争/死活等指标给**建议**，必要时单独触发有限 MCTS。
4. 显示“可结算 / 建议继续下 / 有争议或无法确定”，避免将算法概率当成裁判事实。
5. 真正赛果仍经双方 Pass、适用规则计分及必要的人工确认。

> 当前分支特意改为 **NN-only 智能裁判**。上述 B 是**独立候选功能**，不要未经验证直接替换 A 或悄悄恢复此前的多次 PASS/MCTS 对照流程。

### 5.3 必测局面

9×9 和 19×19：中盘明显领先但尚有大量空点、正常收完官子、留单官/公共气、死棋未提净、双活、打劫/三劫、双方连续 Pass、双方对死子判断不一致、棋盘接近填满。每类保存棋谱、规则、NN 结果和是否满足严格终局条件。

## 6. GTP 命令索引（以 v1.18.x 源码 `knownCommands` 为准）

| 类别 | 命令 |
|---|---|
| 协议 | `protocol_version`、`name`、`version`、`known_command`、`list_commands`、`quit` |
| 棋盘/历史 | `boardsize`、`rectangular_boardsize`、`clear_board`、`set_position`、`komi`、`get_komi`、`play`、`undo`、`showboard` |
| 规则 | `kata-get-rules`、`kata-set-rule`、`kata-set-rules`、`kgs-rules` |
| 模型/参数 | `kata-get-models`、`kata-get-param`、`kata-set-param`、`kata-list-params`、`clear_cache` |
| 搜索/对弈 | `genmove`、`kata-search`、`kata-search_cancellable`、`genmove_debug`、`kata-search_debug`、`stop` |
| 让子 | `fixed_handicap`、`place_free_handicap`、`set_free_handicap` |
| 计时 | `time_settings`、`kgs-time_settings`、`time_left`、`kata-list_time_settings`、`kata-time_settings` |
| 计分/棋谱 | `final_score`、`final_status_list`、`loadsgf`、`printsgf` |
| 搜索分析 | `lz-analyze`、`kata-analyze`、`lz-genmove_analyze`、`kata-genmove_analyze`、`kata-search_analyze`、`kata-search_analyze_cancellable` |
| 原始 NN | `kata-raw-nn`、`kata-raw-human-nn` |
| 性能/调试 | `cputime`、`gomill-cpu_time`、`kata-benchmark`、`kata-debug-print-tc`、`debug_moves` |

具体装入 APK 的构建版本，请运行 `list_commands` 核对；不能只以 master 文档假定可用。

## 7. 命令行模式、训练与部署

| 子系统 | 用途 |
|---|---|
| `gtp` | GTP 对弈及分析 |
| `analysis` | JSON 多局面并发批量分析 |
| `match` | AI 对抗、参数/棋力评估 |
| `selfplay` | 自对弈数据生成 |
| `gatekeeper` | 新模型对比筛选 |
| `benchmark` | 推理与搜索性能测量 |
| `genconfig` / `tuner` | 配置生成和设备调优 |
| 模型训练管线 | PyTorch 训练、数据处理、模型导出、循环自对弈 |

命令行子命令依具体发行版本和编译选项而异，可用本机 `katago help` / `katago version` 验证。

常见推理后端：CPU/Eigen、CUDA、OpenCL、Metal、TensorRT 等；v1.18.x 还在扩展 ROCm、ONNX Runtime、OpenVINO 等支持。**RK3588 RKNPU / RKNN 并非现成官方原生后端**，需要导出/转换网络、算子适配、精度比对并封装 JNI 接口；不要把 Intel NPU 支持误读为 RKNN 支持。

## 8. Android 项目功能开发清单

| 优先级 | 功能 | 现状/下一步 | 验收目标 |
|---|---|---|---|
| P0 | 智能裁判 NN 快速估分 | **已接入**；保持独立 NN-only | 一键出预测，不改棋局 |
| P0 | 智能裁判规则/文案 | 已有提醒；明确“预测 ≠ 判定结束” | 不误报正式终局 |
| P0 | 棋谱保存 SGF | 待核对既有存储代码并集成 | 棋手/贴目/手顺/结果可重读 |
| P0 | 逐手复盘 | 待集成 | 上/下一手、手数、变化对照 |
| P0 | 目数/胜率曲线 | 待集成 | 每步可看趋势 |
| P1 | 归属热力图 | 待集成 | ownership 显示与缩放适配 |
| P1 | 推荐落点与变化图 | 待集成 | top N/PV/搜索量设置 |
| P1 | 更严格的终局检测 | **实验**：Pass-Alive + NN + 可选搜索 | 不把“不确定”误判为“未结束” |
| P1 | 官子提示、争议位置 | 实验 | 能显示理由且允许继续下 |
| P1 | 失误分析/每手损失 | 待集成 | 整盘质量统计与回放 |
| P2 | 死活题库训练 | 待集成 | 题库、验证、提示、分级 |
| P2 | Human SL 人类棋风 | 待集成 | 模型切换与棋风标记 |
| P2 | 11/15 路及更多模型 | 需按 APK 版本测试 | UI、引擎、模型都通过 |
| P2 | CPU/GPU 初始化优化 | 保留现有切换功能 | 计时比较、异常可恢复 |
| P3 | RK3588 RKNN NPU | 技术预研 | 同局面一致性、性能/功耗 |
| P3 | 9×9 自我训练 | 独立课题 | 可复现训练与棋力测试 |

开发前先检索现有实现和测试，避免重复造轮子；尤其需以 `rk3588-engine` 工作分支为准，**不能根据落后的 `main` 分支 README 推断功能状态**。

## 9. 参考资料与维护规则

- KataGo 官方仓库：https://github.com/lightvector/KataGo
- Releases：https://github.com/lightvector/KataGo/releases
- GTP 扩展：https://github.com/lightvector/KataGo/blob/master/docs/GTP_Extensions.md
- GTP 命令实现：https://github.com/lightvector/KataGo/blob/master/cpp/command/gtp.cpp
- Analysis Engine：https://github.com/lightvector/KataGo/blob/master/docs/Analysis_Engine.md
- 棋局历史/规则：https://github.com/lightvector/KataGo/blob/master/cpp/game/boardhistory.h
- 自对弈训练：https://github.com/lightvector/KataGo/blob/master/SelfplayTraining.md
- 本项目当前智能裁判说明：[`../tools/README_smart_referee.md`](../tools/README_smart_referee.md)
- 本项目 JNI 后端路线：[`../JNI_BACKEND_ROADMAP.md`](../JNI_BACKEND_ROADMAP.md)

维护约定：每实现一个 App 功能，应同步更新“现状/下一步”和测试入口；原生功能、原始 NN 预测、MCTS 分析、正式计分必须分别标注。版本升级后先用 `list_commands`、模型输出和设备实测修订文档。
