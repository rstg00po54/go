# 真实已计目棋谱：单次 NN vs 记录中的最终胜负/目数

这次不再用合成棋形或 KataGo 的 `final_score` 作为“正确答案”，而是用真实对局 SGF 文件头的 **`RE[B+2]`、`RE[W+0.5]`** 等**已记录计目结果**作为外部对照标签。读取时不追加任何停棋手、落子或虚构最终形势，不重新按 AI 的意见篡改真实成绩。

脚本：`tools/benchmark_real_sgf_nn_android.py`，使用已有的 Android RK3588 GTP/OpenCL 可执行文件、`10b.bin` 模型、`kata-raw-nn 0` 命令。**不修改 C++、不编译 APK、不改变 APP 正式结算。**

## 内置真实小样本

存于 `tools/real_scored_games.json`，每份带公开来源 URL 与原始 SGF 内容：

- **9 路 5 盘**：GitHub `LatinScribe/BetaGoZero` 项目 `DataSet/2015-Go9-super-small` 中的真实服务器对局。文件记录中国规则、7 目贴目和数字型胜负结果，最后两手停棋。
- **19 路 1 盘**：2017-05-23 AlphaGo 对柯洁第一局，GitHub `akitaonrails/frank_go` 的著名棋谱库，原始记录为中国规则、7.5 目贴目、**白胜 0.5 目**。SGF 末尾没有连续两次 pass，脚本只会标识这个差异，**绝不补 pass**。
- **13 路没有冒充已验证样本**：目前内置小样本没有找到相同要求、带来源和最终目数记录的 13 路真实棋谱。可以通过 `--sgf-dir` 加入已有 13 路计目 SGF（也支持扩充 9/19）。

这些是 **真实比赛记载的 `RE` 标签**，但我们没有重新独立核对每盘终局双方移除死子的全过程，因此属于外部结果标签，而不是“所有棋局都证明无争议”的数学标准答案。更不要把 **6 盘 × 2 次** 说成 12 盘独立样本。

## 运行（RK3588）

在 `GoEngine-java` 目录：

```bash
git pull origin rk3588-engine
python3 tools/test_real_sgf_nn.py
python3 tools/benchmark_real_sgf_nn_android.py --preflight
python3 tools/benchmark_real_sgf_nn_android.py --serial 8719e18a71a2a66c --mode gpu --repeats 2
```

默认会把 **内置公开 SGF** 和报告上传到项目现有公开 `ui-test-results` 分支，便于分析。若不希望上传：`--no-upload`。

新增你自己的 9/13/19 路正式计目棋谱：

```bash
python3 tools/benchmark_real_sgf_nn_android.py --preflight --sgf-dir /path/to/your/sgfs
python3 tools/benchmark_real_sgf_nn_android.py --serial 8719e18a71a2a66c --mode gpu --sgf-dir /path/to/your/sgfs --limit 100 --repeats 2
```

**使用 `--sgf-dir` 时默认不上传**私人棋谱和结果！只有显式加入 `--upload` 才会公开上传。不会下载或发送 SGF 到除用户设备以外的服务（显式 `--upload` 除外）。

## 过滤与真实性

只接受独立单线 SGF 对局：

- `GM[1]`，棋盘 `SZ[9/13/19]`（SGF 允许缺省 `SZ` 代表 19），明确 `RU[Chinese]` 与有效 `KM`；
- `RE[B+数字]` 或 `RE[W+数字]`；**拒绝认输、超时、Void、不完整结果、未知结果**；
- 不包含让子／中途改棋／多分支变化图；必须达到基本落子数并按黑白顺序走棋；
- 对连续双 pass 的棋局作 `terminal_passes` 标志；没记录 pass 但有正式数字计目结果的棋局不伪造落子。
- 执行 `loadsgf` 后先通过 `showboard` 的 `MoveNum` 核对是否重放了原始棋谱的全部落子，防止宽容模式静默跳过某手。再读取中国面积规则和原始贴目，发现不匹配就报错而不是继续计算。

每个局面仅执行 `kata-raw-nn 0`，记录 `whiteLead`、`whiteWin`、`whiteLoss`。不调用 `final_score` 或 `kata-fast-final-score` 作为参照。第一次每个棋盘尺寸的模型推理在计时外预热。

## 结果指标

- **实际胜负一致率**：`whiteLead` 的正负与原 SGF `RE` 的黑胜／白胜对比。
- **目差 MAE**：`abs(nn_white_lead - official_white_lead)`，白方视角统一正负号。
- **有正式目数的不同棋谱数量**（`unique_games`）与运行次数（`samples`）分开报告，提供 9/13/19 路和 GPU/CPU 分组。
- 单次 NN GTP 命令耗时（包含传输和解析），与之前 9/13/19 路复杂合成棋谱的速度可做参考。

生成 `build/real_sgf_nn/<时间戳>/endgame.json`、`endgame.csv`、`fixtures.json`、`positions/*.sgf`。

**评估限制：** 有明确计目结果的 19 路职业棋谱不一定记录最终双方确认死子的过程，单次 NN 的结果与正式数子不完全等价。样本量太小绝不宣称“准确率已达到 x%”，后续应扩充到每种棋盘数十至数百盘，有不同比分的真实终局。尤其要注意 13 路样本缺失、模型训练数据可能与公开旧棋谱重叠、重复评估不独立等偏差。
