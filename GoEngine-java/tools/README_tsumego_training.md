# 死活棋训练（第一版：离线 9×9 入门题）

独立功能分支：`feature/tsumego-training`。不修改 KataGo GTP `final_score`、`final_status_list`、CPU/GPU 模型或正常对弈参数，避免与正在进行的终局优化互相干扰。

## 已实现

- 首页增加“死活棋训练 · 9路入门（离线）”，进入独立训练 Activity；返回后仍是原来的对弈页面。
- 使用现成 `GoBoard` 落子、提子、禁入点/简单劫判断和 `GoBoardView` 绘图。
- 8 道**自己编写的一手入门题**：黑白提一子、黑白提两子、逃出打吃、分割成两只简单单点眼。
- 只有玩家的标准落子；无需 KataGo 搜索、网络或 NPU。落点非法会提示，标准解正确后展示讲解并可下一题。
- 提示显示标准坐标/高亮；使用提示完成不计入“独立完成”进度。
- 进度和上次题号保存在 Android SharedPreferences。
- 题目加载时自动验证布局没有零气棋块、答案落点合法，并且落子确实达到预设的吃子/增加气/两眼目标。
- `GoBoard.setupDiagram` 支持非手顺的固定初始布置，设置棋子不计作真实落子，`undo` 能恢复初始图。

## 运行

在 `GoEngine-java` 目录执行：

```bash
git fetch origin
git switch feature/tsumego-training
bash tools/test_tsumego_core.sh
bash build.sh --apk-only
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`bash build.sh --apk-only` 依赖现有 APK 构建环境和已准备的原生文件；源码测试脚本仅需 `javac`，不依赖 Android SDK。

## 当前限制与下一版

这一版是**一手题教学**，题库的某一标准答案只证明该局部练习目标已经达成，**并不证明所有其他走法均失败**。非收录的其他走法只提示“不是本题收录的标准解”，不会冒充 KataGo 判错。

更复杂的真正死活训练需要引入 SGF 题库与变化树，包括双方最强应手、劫、双活和多种正确解，再基于题目答案树判断，不建议把随机 KataGo 胜率直接当成死活题判定器。

**请不要在另一个会话的结算代码合并期间直接把这个功能分支整体覆盖回 `rk3588-engine`**；应在核对冲突后合并或 cherry-pick 本功能提交。
