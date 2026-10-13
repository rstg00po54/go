# 扩大真实棋谱样本：单次 NN vs 已记录终局目数

上一轮的 6 盘样本已经实现胜负 6/6、平均误差约 0.80 目，但**样本量过小**，不能据此判断整体现实准确率。本工具通过公开 SGF 数据集扩大覆盖，不继续把 KataGo `final_score` 当作标准答案。

## 棋谱来源和筛选

`tools/fetch_real_sgf_corpus.py` 通过 GitHub REST Git Tree 接口列出 `LatinScribe/BetaGoZero` 两份公开的 SGF 目录：

- `DataSet/2015-Go9-small`：9 路在线真实对局，共 753 份候选文件。
- `DataSet/2019-1255games`：19 路真实 KGS 对局，共 1255 份候选文件，很多是认输、让子或日本规则，需要严格过滤。

默认目标是**筛出 80 盘 9 路、40 盘 19 路**；这是目标数量，不保证两组都满足条件。Downloader 必须逐份下载并调用已有 `read_recorded_game()` 验证：`RU[Chinese]`、合法数字 `RE[B+目数]`/`RE[W+目数]`、明确贴目、没有让子和中途摆子、多手真实交替落子、棋盘尺寸正确。过滤失败的棋谱不会冒充有效结果；最终以控制台 `accepted` 和 `manifest.json` 为准。

固定采样顺序（固定 seed）、保留每局原始 SGF、来源 URL、SHA-256 和官方 `RE`。不会把来源转为 NN 预测结果。文件放在 `build/real_sgf_corpus`；没有自动上传私人数据到 GitHub。

**仍然缺 13 路正式计目可靠数据**；脚本不拿 9/19 路数据伪称 13 路。若有带 `RU[Chinese]`、数值型 `RE`、无让子的 13 路 SGF，可放到单独目录，用同样的 `--sgf-dir` 参数测试。

## 执行顺序（GoEngine-java 目录）

```bash
git pull origin rk3588-engine
python3 tools/test_real_sgf_nn.py
python3 tools/test_fetch_real_sgf_corpus.py
python3 tools/fetch_real_sgf_corpus.py --nine 80 --nineteen 40
python3 tools/benchmark_real_sgf_nn_android.py --preflight --sgf-dir build/real_sgf_corpus --limit 1000
python3 tools/benchmark_real_sgf_nn_android.py --serial 8719e18a71a2a66c --mode gpu --sgf-dir build/real_sgf_corpus --limit 1000 --repeats 1 --upload
```

`fetch_real_sgf_corpus.py` 需要能访问 GitHub API 与 raw.githubusercontent.com，并发默认 6 个；网络慢可用 `--workers 3`。它只筛选**已记录计目**局，不会对认输局“补算成绩”。**无需重新编译 C++、无需重新编译 APK。**

`--upload` 会把**你这次选中的公开棋谱以及报告**上传到你项目中公开的 `ui-test-results` 分支，便于我分析。这些是第三方公开棋谱。若使用私人棋谱，绝对不要加 `--upload`，默认只保存本地。

脚本默认每局只测一次，因为相同 `kata-raw-nn 0`、同一局面和模型的重复推理不构成更多独立棋局。如要比较抖动可增加 `--repeats 2`，报告中的 `unique_games` 和 `samples` 始终分开。

## 如何读结果

结果为 `build/real_sgf_nn/<UTC时间戳>/endgame.json`，统计：
- 与棋谱 `RE` 的实际胜负一致率；
- 单次 NN 预测 `whiteLead` 与棋谱实际数字目差的平均绝对误差、最大误差；
- 9 路、19 路分别的结果与 RK3588 GPU 单次耗时；
- 每局 `showboard MoveNum` 验证完整重放，贴目、中国面积规则严格一致，拒绝不合格样本。

**限制**：`RE` 是对局记录的比分，不一定完整记录死子争议协商过程；不是独立数子重算。数据集可能偏向某类棋力和计目方式，KataGo 训练时也可能见过公开旧局。不要把筛选后的小样本准确率外推成全部围棋对局的 100%。
