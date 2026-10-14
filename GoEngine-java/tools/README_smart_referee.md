# 智能裁判：单次 NN 估分

当前实现（2026-10-14）：用户点击「智能裁判」，只读取当前局面的 KataGo 原始神经网络估计（GTP `kata-raw-nn 0`）；不进行 MCTS、PASS 对照搜索或 GTP 的原生最终计分。

## 用户界面

- 显示 NN 推测领先方、领先目数（whiteLead）、黑白双方胜率，新增黑白双方预计目数（中间状态栏和弹窗）。
- 目数来自同一次 NN 的地盘归属概率，按中国规则面积估计（棋子+围空，白方含贴目或让子补偿）；与网络单独预测的目差可能略有差异。
- 同时进行本地的简单劫禁、单气棋块检查；若发现问题，只显示风险提醒，不宣称已解决死活。
- 对局仍在进行时显示「继续下棋」；已经双方停棋时显示「关闭」。
- 没有「局面检查」「KataGo 原生裁判」按钮，也不会默认执行 `final_score`、`final_status_list dead` 或 `kata-search_analyze`。
- 快速 NN 估计不会自动落子、停一手、修改棋谱或宣布正式赛果。

仅 NN 的预测不能证明双活、死棋、劫争已经解决，也不能证明官子全部收完。NN 胜率很高不等于棋局已经结束。

## 保留的功能

- 正常 9/13/19 路对弈、CPU/GPU 选项、胜率/估分显示保留原逻辑。
- 双方停棋后的已有中国规则数子和人工确认死子流程保持不变；正式结果仍需要人工确认。
- 内部用于历史烟雾测试的旧裁判画面仍可通过测试入口调用，但已不再执行黑白双方 MCTS 搜索。
- KataGo 通用 GTP 命令保持在引擎底层供其他功能使用；已删除专为智能裁判添加的四次 PASS 对照包装器。

## 验证

修改 Java 后需要重新编译 APK，但不需要重新编译 KataGo native so。如果原生库已经成功构建，在 `GoEngine-java` 目录执行：

```bash
git pull origin rk3588-engine
python3 tools/test_smart_referee.py
python3 tools/test_smoke_android_referee_app.py
bash build.sh --apk-only
```

运行后可查看日志：

```bash
adb logcat -d -v time | grep 'REFEREE_FAST_NN' | tail -10
```

预期只出现 NN 估分，不再出现本功能的 `REFEREE_ENDGAME_RESULT`、`KATAGO_NATIVE_ENDGAME`。其他对弈功能可能正常发出 GTP 搜索命令，不能据此推断裁判仍在执行 MCTS。

2026-10-13 至 2026-10-14 的 PASS 对照实验及原生 Benson 活棋算法性能报告保留在 `GoEngine-java/reports/`，仅供历史比较，**不属于当前功能**。
