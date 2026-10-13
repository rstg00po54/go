# 智能裁判：KataGo 原生命令（2026-10）

普通界面现在保留快速 NN 估分入口；详细裁判直接使用 KataGo 原生 GTP 命令，不再通过自定义死活启发式判断。

- 点击「智能裁判」：先运行单次 kata-raw-nn 0，显示估计目差与胜率；同时做无需额外 NN 的棋规检查：当前有劫禁就提示不能直接确认终局、有单气棋块就提示复核死活（单气不等于必死）。
- 选择「KataGo 原生裁判」：在当前棋形执行 final_score 与 final_status_list dead，展示预测赛果与死子，棋盘临时用红叉提示；选「查看棋盘」可保留标记，选「清除标记」立即清理；下一次棋局落子或重新开局也会自动清理。
- 不停手、不回退、不发送落子。中盘的 final_score 是估计，不是既定的终局计分；复杂劫、双活和未收官子可能有误差。
- GTP 的 final_status_list dead 可含多行坐标，已修复原来只解析首行的问题。
- 旧的 startSmartReferee() 仍仅由旧版自动化烟雾测试入口使用，以便保留历史回归。普通 UI 已不走这套双边搜索和 Java 启发式算法。

KataGo v1.15.0 原生源码中，final_score 采用至少 50 次访问、final_status_list 至少 100 次访问（实际下限还与线程数相关）。GTP 响应等待上限 60 秒/30 秒不等于通常耗时。详细裁判增加了先显示计分结果的进度提示，logcat 的 REFEREE_NATIVE_GTP 行包含 scoreMs、deadMs 与总耗时，便于分别比较手机和 RK3588、CPU 和 GPU。无需改 native so。

需重新编译和安装 APK。手机/RK3588 均应验证 9/13/19 路，中盘/终局，以及 CPU/GPU 的响应、标记清除和历史棋步不变。final_score 可能需要较长时间，并非单次 NN。

---

## 2026-10-13 快速原生接口的真实耗时

在 GitHub Linux x86_64 CPU Eigen AVX2 上，用库中同一批 19 个局面、每局两次，与完整原生计分对照：
- KataGo v1.15.0：单次 kata-raw-nn 0 的中位数 **6.27ms**；不落子的 12 次访问搜索中位数 **69.45ms**。
- KataGo v1.18.1：分别是 **10.16ms** 和 **124.43ms**。二者编译和运行环境有所不同，不能据此断言旧版算法更快。
- 完整 final_score + final_status_list dead 在 9/13/19 路常需要几秒；**不得在每次普通智能裁判点击时强制执行**。
- 快速 NN 的 whiteLead 与正式 final_score 是不同概念；12 次搜索的候选落点不证明棋局没收完，更不能判定棋子生死。
- 手机和 RK3588 的 CPU/GPU 响应时间仍需安装 APK 后实测；以上仅是 Linux runner 的参考。
- 基准复测：.github/workflows/katago-referee-fastpaths.yml；报告在 GitHub Actions 附件及 GoEngine-java/reports/20261013_katago_fastpaths.*。

## KataGo 实机 GTP 劫争回归（GitHub Actions）

`.github/workflows/katago-native-ko-check.yml` 会下载官方 Linux CPU 引擎 / 从源码构建 v1.15.0，然后运行 `tools/test_native_katago_ko.py`。两版均使用仓库原有的 `10b.bin` 模型及中国规则配置，测试真实 `cgos9_3` 第 64 手和人工构造的标准单劫。具体 CI 结果见 GitHub Actions。

- **v1.15.0**: 有劫时 `final_score` 与 `final_status_list dead` 依旧给预测结果；GTP `play` 对简单劫立即回提返回 `? illegal move`。
- **v1.18.1**: 计分和死子预测仍然返回；GTP `play` 的宽松合法性已移除简单劫禁检查，立即回提返回 `=`。KataGo 搜索和对局规则的严格合法性不可与宽松 GTP `play` 混同。
- 因此不能用 `final_score` 是否返回、也不能跨版本用 GTP `play` 成功与否，判断棋局已结束。智能裁判应以本地 `GoBoard` 的严格劫禁及可用搜索证据作独立检查。

## 可选的局面检查（官子、打吃、劫）

快速 NN 估分弹窗增加「局面检查」按钮；不改变已有快速估分速度。仅点击时执行一次有限的原生 KataGo `kata-search_analyze` 搜索（12 次访问上限、0.5 秒搜索时间上限），不会落子。棋盘规则层仅复用现有的棋块/气计算，显示当前单气棋块数量和简单劫禁点。日志：`REFEREE_UNFINISHED_CHECK`。

**没有自动“收官完成”断言**：KataGo 并无独立的“官子收完、打吃已解决、劫争彻底终结”GTP 布尔接口。短搜索第一候选不是 PASS，也不能证明没下完；第一候选是 PASS，同样不能证明已下完。单气棋块可能已死，简单劫禁不等于所有劫争。结果只展示线索，不修改棋局或自动停一手。需现场测试手机/RK3588 CPU/GPU 以及真实官子/劫争棋谱后，再考虑更强的分类策略。

## KataGo 原生归属不确定性（2026-10-13）

「局面检查」现在只通过 KataGo 的 `kata-search_analyze ... rootInfo true ownershipStdev true` 读取 NN/MCTS 原生输出；限 12 次访问、0.5 秒，并且不落子、不调用 Java 自制死活/双活判断器。APP 展示 KataGo 地盘归属波动数值（最大值及大于 0.25 的交叉点数）；**0.25 是仅供提示的可视化门槛，不是死活定理**。若发现波动较大的区域，提示「可能有死活、攻防或收官争议，建议继续下棋」；无波动也不能确认收官。

KataGo v1.15.0 的 `final_status_list seki` 接受命令但不会输出双活坐标。原生 `noResult` 在双劫棋形上也接近零，因此不能据此直接识别复杂劫争。**不能承诺自动识别所有双活/复杂劫/未解死活**。已有的精确劫禁和单气只是围棋规则提醒，不负责死活裁决。

点击「继续下棋」：仍在对局时关闭提示并保持当前棋谱；如果连续两次 PASS 停棋，则复用已有 `resumeFromReferee()`，同时撤销引擎与棋盘的两次 PASS 后恢复对弈。没有人工选择时不主动修改棋盘。真实原生数据测试：[Native KataGo uncertainty evidence](https://github.com/rstg00po54/go/actions/runs/38404897001)。

## 四状态官子裁判（2026-10-13）

界面只显示四个结果：**有打劫 / 官子未收完 / 不能确认 / 裁判完成**。严格劫禁优先。棋盘没有明确简单劫禁时，调用 KataGo v1.15.0 原生非落子搜索：黑方正常搜索、黑方强制 PASS、白方正常搜索、白方强制 PASS，四次使用 `kata-search_analyze`，单次最多 24 visits / 0.5s；读取 KataGo 的 `scoreLead` 以及 `ownershipStdev`。没有额外落子、PASS 或自制死活算法。保留原引擎搜索参数并恢复。

- **有打劫**：棋盘已有简单劫禁点，不需要用 GTP `play` 误判。
- **官子未收完**：在尚未停棋时，任一方正常搜索相对 PASS 的 KataGo 预计收益达到 1.5 目，提醒继续下棋。这里可能是官子、攻防或死活，不承诺把它们精确区分。
- **不能确认**：引擎没返回足够数据、尚未双方停棋、归属/单气存在争议、或已有停棋但仍发现可能有收益。
- **裁判完成**：双方已经连续 PASS，当前无明确劫禁及单气，KataGo 未发现较大归属波动，双方相对 PASS 的收益均不超过 0.5 目。此处是 AI 的**官子检查完成**，未调用耗时的 `final_score` 或 `final_status_list dead`，仍需用户选择「查看胜负」让 KataGo 正式估分和死子预测。

1.5 和 0.5 是针对 KataGo **原生预测分差**的保守提示门槛，并非 Java 自制官子算法或数学保证。实际设备受模型、搜索预算和规则影响；不能用短搜索替代专业裁判、双活判定或复杂劫争处理。GTP 强制 PASS 功能已在 [19 个真实/合成局面的官方 KataGo 实测](https://github.com/rstg00po54/go/actions/runs/38414463854) 验证；若查询失败，统一「不能确认」。

原有「智能裁判」单次 NN 估分入口不变；「KataGo 原生裁判」详细死子判断继续独立存在。只有用户点击「继续下棋」且此前双方已停棋时，才恢复对应两次 PASS；其余检查只读不改动棋谱。

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
