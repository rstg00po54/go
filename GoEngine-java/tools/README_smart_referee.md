# 智能裁判：KataGo 原生命令（2026-10）

普通界面现在保留快速 NN 估分入口；详细裁判直接使用 KataGo 原生 GTP 命令，不再通过自定义死活启发式判断。

- 点击「智能裁判」：先运行单次 kata-raw-nn 0，显示估计目差与胜率。
- 选择「KataGo 原生裁判」：在当前棋形执行 final_score 与 final_status_list dead，展示预测赛果与死子，棋盘临时用红叉提示；关闭弹窗时清理标记。
- 不停手、不回退、不发送落子。中盘的 final_score 是估计，不是既定的终局计分；复杂劫、双活和未收官子可能有误差。
- GTP 的 final_status_list dead 可含多行坐标，已修复原来只解析首行的问题。
- 旧的 startSmartReferee() 仍仅由旧版自动化烟雾测试入口使用，以便保留历史回归。普通 UI 已不走这套双边搜索和 Java 启发式算法。

需重新编译和安装 APK。手机/RK3588 均应验证 9/13/19 路，中盘/终局，以及 CPU/GPU 的响应、标记清除和历史棋步不变。final_score 可能需要较长时间，并非单次 NN。

---

## 历史实验说明（仅供参考）

# 智能裁判实验版：停棋后人工复核死活 + 中国规则数子

入口：双方连续停棋、APP 的单次 NN 快速估算完成后，点原来的右下角 `裁判/对照` 按钮，选择：

- **智能裁判（手动复核死子）**：重新调用已有的 `kata-raw-nn 0`，根据 NN `whiteOwnership` **整块棋**分为“AI 建议死”（红圈叉）、“需复核”（黄圈）和普通棋块。使用比旧 `FastEndgameScorer` 更保守的阈值，不会自动做无异议裁判宣告。
- **深度对照（KataGo 搜索）**：仍保持旧的 `final_score` + `final_status_list dead`；不受新模式影响。

进入智能裁判模式后，**点击任意棋子会整块切换“拟死/活”状态**，棋盘实际落子、提子记录和原始历史不被修改。画面显示实时中国规则面积预览（黑/白子数 + 单方包围空点，空点双方接壤不计；贴目和让子维持原设置）。

确认步骤：

1. 对局玩家逐块检查红叉（AI 仅提出候选，没有证明死活）、黄色提醒、双活/劫争/未收完官子。
2. 需要修改判定时，点击任意一颗棋子可整块修改；点击 `确认裁判` 检查中国规则数子结果。
3. 选择 `已复核并确认` 后才发布“确认数子”；也可以选择 `恢复 AI 建议` 或 `退出裁判`，不会覆盖原来的单次 NN 快速预测。
4. 已确认后按钮变成 `复核裁判`，可以重新打开并修改整个棋块；中途退出会恢复上一次确认的死子名单，不会意外覆盖旧结果。

**设计限制：** 这是试验性的终局**辅助工具**，目前正式面积计算复用 APP 既有 `GoBoard.countChineseScore` 的 Java 中国规则洪泛数子，**不是**在 C++ 新写了一套有争议的死活裁决。裁判 AI 建议也仍基于一次 NN 归属预测，不能证明死活，也不能自行处理复杂劫争/双活的最终争议；人机对局需要玩家复核、双人对局需要双方确认后才可当作本地记录。后续如果需“全流程 C++ 规则计分”，再把用户确认的死子名单传给 KataGo 原生数子接口。

## 验证与安装

在 `GoEngine-java` 根目录：

```bash
git pull origin rk3588-engine
python3 tools/test_real_sgf_nn.py
python3 tools/test_smart_referee.py
python3 tools/test_fetch_real_sgf_corpus.py
python3 tools/fetch_real_sgf_corpus.py --nine 80 --nineteen 20
python3 tools/benchmark_real_sgf_nn_android.py --preflight --sgf-dir build/real_sgf_corpus --limit 1000
python3 tools/benchmark_real_sgf_nn_android.py --serial 8719e18a71a2a66c --mode gpu --sgf-dir build/real_sgf_corpus --limit 1000 --repeats 1 --upload
```

**真实棋谱测试不需要重编 KataGo 和 APK**。智能裁判 UI 属于 Java 代码修改，需要重编 APK，但不需要重编 KataGo C++。如果之前已经成功编译 CPU/GPU JNI so，可快速：

```bash
bash build.sh --apk-only
adb -s 8719e18a71a2a66c install -r app/build/outputs/apk/debug/app-debug.apk
```

`--apk-only` 会重用原生 so；若从未编译过 native so，再用 `bash build.sh`。真实棋谱测试原来只依赖单次 NN，**不用重装 APP**。

还未在 RK3588 上实际安装或点选验证。运行 `python3 tools/test_smart_referee.py` 可在主机 JDK（不需要 Android）检查 Java 核心死子整块切换、9/13/19 路尺寸、规则数子和无棋盘副作用；APP 的触控与对话框仍需安装后测试。


大批量真实棋谱下载与筛选、公开报告的说明见 [README_expanded_real_sgf.md](README_expanded_real_sgf.md)。
