# KataGo 终局结算：保留原始结果的版本（2026-10-12）

## 为什么撤回默认快速数目

前一版把 KataGo 的 `final_score` 省掉，只调用 `final_status_list dead` 后由 Java 计算当前局面的中国数子，实测在 RK3588 GPU 9×9 用了 1.92 秒。但 **不是等价替代**：`final_score` 包含改变贴目、反复搜索胜率，预测最终目差。双方死子清单相同，也不意味着它和当前局面的中国数子目差相同。不能为了速度让用户的最终胜负结果悄悄变了。

## 当前默认行为

恢复原来的 KataGo 结算命令和顺序：

```text
双方停棋
  -> final_score             // 保留原算法和返回的胜负目差
  -> final_status_list dead  // 保留 KataGo 的死子判断
  -> kata-raw-nn 0           // 保留棋盘归属率显示
  -> Java 中国数子核对      // 保留差异日志与「约」标记
```

现在 `final_score` 一返回，主界面就先显示 KataGo 的搜索预测胜负，在后台继续运行死子判断；这比等到所有命令完成才显示胜负更早。它**不是让 final_score 本身更快**，不宣称同样的准确度在 2 秒完成。

同时，游戏终局时递增 `winRateSession`，取消独立胜率分析引擎中尚未开始的任务，避免无效搜索与主结算争抢设备资源。这不会影响主 KataGo `final_score` 参数、访问信息和计算公式。已经运行中的胜率分析不会被强制中断。

完结后按钮显示「已结算」，而不是要求用户额外点击「深度估算」才能获得原来的算法结果。如果 GTP `final_score` 失败但死子结果可用，才允许点击「重新估算」。

## 运行

在 GoEngine-java 目录：

```bash
git pull origin rk3588-engine
python3 tools/test_full_game.py
python3 tools/test_app_ui_units.py
bash build.sh --apk-only
adb -s 8719e18a71a2a66c install -r app/build/outputs/apk/debug/app-debug.apk
python3 tools/test_app_ui.py -s 8719e18a71a2a66c --backends gpu --size 9 --full-game --game-max-actions 110 --settlement-timeout 180
```

如果 CPU/GPU JNI 库缺失，改用普通 `bash build.sh`。如需明确查看原来胜负算法耗时，在 Logcat 中筛选：

```text
ENDGAME_TIMING phase=score_done elapsedMs=... scoreMs=...
ENDGAME_TIMING phase=full_done elapsedMs=... scoreMs=... deadMs=...
```

## 尚未解决的核心速度问题

源码 `cpp/program/playutils.cpp` 中的 `PlayUtils::computeLead()` 会反复调用 `evalKomi()`，每个新的贴目都可能触发一轮 50-visits 搜索。`final_status_list dead` 另执行 100-visits 的归属率搜索。这一版保证不通过省略计算改变数值口径，但 **没有证据能把总计算时间从约 8 秒压缩到 2 秒**。

接下来真正的性能工作是把相同 SGF 的单线程和多线程原生 GTP 速度及最终结果逐一对比；若线程数提升后结果变化，不能直接替换默认行为。切换 `friendlyPassOk=false` 会改变规则下双停棋的处理方式，也不能未经验证就上线。
