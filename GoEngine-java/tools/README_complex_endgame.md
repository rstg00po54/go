# KataGo C++ 复杂死活与数子压力测试（固定 SGF）

只增加 **Python 测试棋谱、解析和统计**；保留已编译的 `kata-fast-final-score` 命令、原版 `final_score` 和原版 `final_status_list dead`。不修改 Android APP，也不需要再次编译 C++/APK。

## 复杂局面覆盖什么

`complex_endgame_fixtures.py` 提供 9 份确定性合成棋盘：

| 名称 | 路数 | 特别关注 |
|---|---|---|
| `ko_shape9` | 9 | 单子提劫形，但没有历史劫禁着 |
| `false_eye9` | 9 | 假眼形 + 两颗仅剩一气的对方棋 |
| `mutual_liberties9` | 9 | 双方大块棋面临狭窄的公共气 |
| `ladder_pressure9` | 9 | 梯形接触、一气棋块，不保证真实征子成立 |
| `multi_atari9` | 9 | 两个分离的一气黑棋块：F5 与 H5 |
| `double_ko_shape13` | 13 | 两个相反颜色的提劫形：F8 与 K7 |
| `mutual_liberties13` | 13 | 两块大棋在狭窄公共气周围对峙 |
| `false_eye13` | 13 | 两种颜色各有假眼形 |
| `mixed_fight19` | 19 | 全盘同时出现提劫形、假眼和多块弱棋 |

**棋形标签不等于真死活结论**。这批棋谱是静态 AB/AW 摆子，没有真实交替落子历史；即使名字包含 `ko`，也不能测试劫禁着或超级劫历史。`mutual_liberties` 不代表已证明双活。这批测试的目的是找出两个算法在哪些困难形状上产生分歧，不提供经过专业棋谱核对的标准答案。

每个棋盘都有双方至少两个简单的角部眼，未直接摆出零气非法棋块；预标注 `atari` 只表示**当前棋块恰有一口气**，不表示可以断言它死亡。新增 `test_complex_endgame_fixtures.py` 检查这些几何性质、SGF 大小和结果摘要。

## 运行

在 `GoEngine-java` 目录：

```bash
git pull origin rk3588-engine
python3 tools/test_endgame_benchmark.py
python3 tools/test_native_fast_score.py
python3 tools/test_complex_endgame_fixtures.py
python3 tools/benchmark_native_fast_score_android.py --serial 8719e18a71a2a66c --mode gpu --suite complex --threads 1 --repeats 2
```

建议先验证几种代表性复杂局面（更快完成）：

```bash
python3 tools/benchmark_native_fast_score_android.py --serial 8719e18a71a2a66c --mode gpu --suite complex --positions ko_shape9,false_eye9,multi_atari9,double_ko_shape13,mixed_fight19 --threads 1 --repeats 2
```

完整 `--suite complex` 会跑 9 种局面、每种 2 次，**每次调用慢版原生 KataGo 搜索**，可能需要数分钟。由于历史结果中原版搜索就要约 8 秒/盘，这不是“整个实验仍只需要几十毫秒”。可增加 `--timeout 300` 给大棋盘更多单条命令时间。测全部历史 3 份+新 9 份可使用 `--suite all`；只测旧的三份则使用 `--suite baseline`。`--no-upload` 不上传公开报告。

测试会自动读取现有本地原生 GPU 可执行文件 `build/katago_android_arm64_opencl/libkatago_exec_opencl.so`。已经编译成功且含 `kata-fast-final-score` 命令的话，**不需要重编译，也不需要安装 APK**。

## 结果判断方法

每份每次固定局面的 `endgame.json` 保留以下四项，不混淆其含义：

- `native_dead` vs `original_dead`：原生快版和原版搜索判死名单；分别看 `dead_only_native`、`dead_only_original`。
- `nn_white_lead` vs `original_white_lead`：两种**预测目差**（NN 一次 vs 原版 MCTS 搜索）。
- `native_white_lead` vs `original_white_lead`：**当前规则数子 vs 未来搜索预测**；这个差异本身不是计算错误。
- `native_ms` vs `original_score_ms + original_dead_ms`：各自耗时，注意两者不完全等价。

新 `summary` 按棋谱统计 `dead_disagreements`、`nn_to_original_delta_average_abs`、`original_prediction_range` 和原版死子名单的不同版本。若原版自己反复跑结果不一致，应标出**原版不稳定**，不能把其中一次的死子名单当标准答案。

结果只标记 `COMPARISON_ONLY`，**不声称死活准确率或标准判胜率**。下一步应引入真实对局 SGF 及人工确认死子的标准数据集，才有条件讨论“准确”。

默认报告通过项目现有脚本上传到 **公开** `ui-test-results` 分支。私人棋谱必须使用 `--no-upload`。
