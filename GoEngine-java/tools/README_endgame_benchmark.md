# RK3588 固定 SGF 终局：CPU / GPU 性能测试

目标：不用自动下棋生成两盘不同的局面，而是把 **同一个固定 9×9 棋盘状态**送给 KataGo CPU/Eigen 与 GPU/OpenCL，逐条测量结算命令。这是独立的 Android 原生 GTP 基准，**不需要重新编译或安装 APK，也不等于 APP 的 UI 结算总耗时**。

## 运行

先在 GoEngine-java 目录更新代码，执行不连接硬件的检查：

```bash
git pull origin rk3588-engine
python3 tools/test_endgame_benchmark.py
```

先只跑 GPU 一次，检查固定 SGF 能否成功加载：

```bash
python3 tools/benchmark_endgame_android.py --serial 8719e18a71a2a66c --mode gpu --repeats 1
```

确认没有 GTP/OpenCL 报错后，再跑 CPU+GPU 同局面对比：

```bash
python3 tools/benchmark_endgame_android.py --serial 8719e18a71a2a66c --mode both --threads 1 --repeats 2
```

可选参数：--positions simple,territory,tactical；--timeout 150（每条 GTP 命令允许的最长秒数）；--cpu-binary、--gpu-binary；--no-upload。

## 三份固定位置

内置 simple（两边明确两眼的简单位置）、territory（双方稳定活棋和较长的边界）、tactical（双方稳定活棋、中央存在弱棋块）三种合成固定棋盘。运行时生成含 AB/AW 初始布局的 SGF，使用 GTP loadsgf 命令读取。**它们是固定棋盘快照，不是专业棋手实际的连续落子棋谱；所谓复杂程度也未经人工死活认证。**

每份棋盘都经过离线基础检查：大小 9×9，双方各有至少两个被自己棋子包围的眼位，所有设置的棋块都有气。各后端使用相同 SGF SHA256、相同 10b.bin 模型、Chinese AREA 规则和 5.5 贴目；默认 numSearchThreads=1、maxVisits=8、maxTime=8。注意 KataGo 终局估算可能有不依照普通落子 maxVisits 的额外搜索。

## 测哪些阶段？

每一轮依次执行 loadsgf，验证 kata-get-rules，清理搜索缓存，再分别测：

- final_score：终局胜负目差估算
- final_status_list dead：死子判断
- kata-raw-nn 0：固定棋盘的神经网络原始评估

时间用主机 perf_counter 对 GTP 命令发出到回复完整到达计时，包含 ADB/GTP 传输，**不等于 GPU kernel 耗时，也不包括 Android Java/界面显示**。首轮原始神经网络评估会预热，不计入后续统计。

此外工具复现了 APP 里 GoBoard.countChineseScore 的死子移除和空点连通域规则，记录本地中国数子与 GTP final_score 的目差。这可以调查先前出现的 2 目差异，但位置不同，不能直接断言原来的那一局一定存在同样的问题。

## 输出与自动上传

报告目录：build/endgame_benchmark/日期时间/，包含 positions/*.sgf、fixtures.json、endgame.csv、endgame.json、CPU/GPU stderr 日志。报告也记录模型和可执行文件的 SHA256、KataGo 版本以及 CPU/GPU 结算差异警告。

测试成功后自动上传小型 SGF/JSON/CSV 到**公开** GitHub ui-test-results 分支，方便直接复查；使用 --no-upload 可关闭。失败时保留本地 CSV/日志，命令会返回非零退出码，便于定位。

## 构建产物要求

本脚本复用现有 benchmark_katago_genmove_android.py 的运行环境，需要 adb、Python3、readelf、ARM64 的 KataGo CPU/Eigen 和 GPU/OpenCL 可执行文件，以及 app/src/main/assets/engine/10b.bin 和 default_gtp.cfg。CPU 默认找 app/build/generated/katagoJniLibs/arm64-v8a/libkatago_exec.so 或 ~/.cache/goengine_katago/build_android_arm64_eigen/katago；GPU 默认找 build/katago_android_arm64_opencl/libkatago_exec_opencl.so。缺少时不会自动下载或重编，请用参数指定本地真实的 ARM64 可执行程序。

如果以后要求 APK UI 也加载同一盘棋，则还需要为 Java GoBoard 和 JNI 引擎增加同步的 SGF 导入功能，不能只向后端加载棋谱。
