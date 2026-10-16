# 离线死活入门训练

本次增加一个真正可进入的训练页面（不是“功能开发中”占位按钮）。

## 入口

首页 **死活训练** 图片或底部 **训练** 按钮 → `TsumegoActivity`。
和棋谱复盘页一样，训练完全独立于正在运行的 KataGo，对局模型、
NPU 推理、正在进行的棋局数据均不修改；没有网络连接也可以做题。

## 第一批练习

内置 8 道原创 9×9 **一手提子** 入门题。
包括黑先、白先、单子/两子/三子，以及中央、边线、角上的提子。
被攻击的目标棋子会用金色圈标记，题面提示当前轮到哪一方落子。
答对后提掉对应棋子并显示原理；答错只给提示，不更改棋盘，
可以自行重试。另有“提示”“重做”“上一题”“下一题”。

已完成题目以位标记形式记录在本机私有 `SharedPreferences`，
再次进入保留完成进度和上次题号。不记录个人隐私数据，也不发送网络请求。

训练棋盘基于独立 `GoBoard.forTrainingPosition()` 初始化双方棋子，
不会在真实对弈的棋谱中添加练习落子。题库写在
`app/src/main/java/com/badukai/game/TsumegoLessons.java`，
训练界面位于 `app/src/main/java/com/badukai/TsumegoActivity.java`。

这是**死活基础**的首批一手吃子练习，还不包含复杂两眼做活、
手筋、打劫及多分支变化。后续可以增加多步应答、分级题库、
错误复习和 AI 解题讲解，不必先运行 NPU 性能测试。

## 编译

```bash
cd /mnt/hgfs/D/code/go/GoEngine-java
git pull --ff-only
ALLOW_4K_RKNN_RUNTIME=1 bash build.sh --apk-only
```

以上使用现有 RK3588 4KB page-size JNI 缓存构建调试 APK，
不会重新训练或转换任何模型。若不是 RK3588 4KB 设备，
不要使用 `ALLOW_4K_RKNN_RUNTIME=1` 的兼容性例外。
GitHub Actions `offline_tsumego_ui.yml` 只进行 Java 编译检查。
