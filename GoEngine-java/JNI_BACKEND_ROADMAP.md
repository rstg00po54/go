# KataGo JNI / CPU-GPU-NPU 改造路线图

> 工作分支：`rk3588-engine`  
> 最终目标：Android 使用真正可通过 `System.loadLibrary("katago")` 加载的 `libkatago.so`；不再依赖 `ProcessBuilder` 启动伪装成 `.so` 的 PIE 可执行文件。一个 JNI 主库支持启动时选择 CPU / GPU，后续扩展 RK3588 NPU。  
> 状态基准：2026-10-09。每完成并验证一个阶段再勾选对应任务。**计划已记录 ≠ 实现已完成。**

## 当前基线（必须保留）

- [x] Android ARM64 KataGo CPU/Eigen 可执行文件通过 NDK/CMake/Ninja 编译。
- [x] `./build.sh` 经用户 Ubuntu 环境验证可完成 APK 构建（含 JNI 库目录打包流程）；默认并行 8 任务、增量编译、输出 APK 路径。
- [ ] 用户设备上对当前 APK 的引擎启动、落子、分析做一次完整回归验证。
- [ ] 记录用于对比的 CPU GTP 指令及结果（`name`、`boardsize`、`play`、`genmove`、`undo`、`kata-raw-nn`）。
- [ ] CPU JNI 替代方案验收前，不删除现有 `libkatago_exec.so` 和 `ProcessBuilder` 回退路径。

## 前置实验：Android GPU/OpenCL 性能测试（不改变现有 CPU APK）

- [x] 提供独立 OpenCL 交叉编译脚本：`bash tools/build_katago_from_source.sh opencl`，使用单独的 OpenCL 构建/输出目录，不覆盖 CPU 产物。
- [x] 提供自动 CPU→GPU benchmark 脚本：`bash tools/benchmark_katago_android.sh` 默认依次测试 CPU 和 GPU，按搜索线程统计 visits/s、nnEvals/s、batch 与 GPU 提升率，保存 `cpu.log`、`gpu.log`、`summary.csv`；原 `cpu` / `gpu` 单独测试参数仍可用。
- [x] 从 Android ARM64 设备读取 `/vendor/lib64/libOpenCL.so` 到本地缓存（约 42 MB），本机已找到 OpenCL 头文件；不提交供应商库。
- [x] 用户 Ubuntu + Android NDK 27.2.12479018 已完成 KataGo OpenCL AArch64 PIE 编译与链接（`[117/117] Linking CXX executable katago`），输出 `build/katago_android_arm64_opencl/libkatago_exec_opencl.so`。
- [x] Android 真机上的 GPU KataGo 已加载厂商 OpenCL Runtime，识别 Mali-G610 r0p0 / OpenCL 3.0，创建 OpenCL context；原先 `libGLES_mali.so` SONAME 找不到的问题已通过临时复制 vendor OpenCL 库解决。
- [x] **完成 GPU 调优和实际 benchmark 验证**：修复 `//.katago` 缓存目录，并对 Mali-G610 跳过存在驱动编译错误的 `hGemmWmma`；2026-10-09 GPU 调优成功，已保存 `tune11_gpuMaliG610r0p0_x19_y19_c128_mv8.txt`，FP16Storage=true、FP16Compute=true、FP16TensorCores=false。
- [x] 同设备 19x19 / 10b / 100 visits / 2 搜索线程 / 2 positions CPU/Eigen 基线：`39.99 visits/s`、`38.60 nnEvals/s`、总测试约 5.1 秒（用户 2026-10-09 日志）。
- [x] 同设备 19x19 / 10b / 100 visits / 2 搜索线程 / 2 positions GPU/OpenCL：`43.48 visits/s`、`41.33 nnEvals/s`、约 4.6 秒；较 CPU `39.99 visits/s` 约快 `8.7%`。首次调优约 104 秒（18:17:31–18:19:15）。该样本仅 2 个局面，结果不代表稳定性能优势。
- [x] 记录 ADB Shell 测试的动态加载限制：PIE 引用的 SONAME 为 `libGLES_mali.so`，默认搜索路径找不到；复制手机 `/vendor/lib64/libOpenCL.so` 到 `/data/local/tmp/katago_bench/libGLES_mali.so` 并设置 `LD_LIBRARY_PATH` 后测试成功。
- [ ] **另行验证 Android APP/JNI 场景的 linker namespace 和 SELinux 访问权限**；ADB Shell 能跑不代表 APK 内可直接访问供应商驱动。
- [x] **2026-10-10 多线程复测完成**：19x19、10b、200 visits、5 positions，搜索线程 2/4/6/8；GPU 在 8 线程达到 115.91 visits/s，相比 CPU 同为 8 线程的 79.33 visits/s 快约 46.1%；2 线程时 GPU 略慢。
- [ ] 对 APP 实际的短时落子设置（当前 config `maxVisits=20`、`maxTime=0.4`）验证用户可感知延迟；关注更长测试时的发热、耗电和降频，不能直接将 benchmark 的 8 线程结果认定为 APP 最佳配置。
- [x] Android 真机 GTP `genmove` 延迟脚本已运行成功：`bash tools/benchmark_katago_genmove_android.sh`（CPU→GPU，16/24/32 搜索线程、19x19、20 visits/步、maxTime 0.4s，10 局面×2 次）；本次 CPU 平均 433–441ms、GPU 约 439ms，所有结果接近 400ms 时间上限，**不能据此得出两后端推理速度相同的结论**。已新增从 KataGo `ogsChatToStderr` 的 `MALKOVICH:Visits` 输出记录每步真实 root visits、平均 visits 与触及访问上限比例（此增强待真机复测）。下一步使用 `bash tools/benchmark_katago_genmove_android.sh --max-time 60 --threads 2,4,6,8,16,24,32` 排除 0.4s 截断，再按 APP 限时负载评估实际体验；本脚本测的是 ADB Shell GTP 往返耗时，而非 APP UI 全链路。

### 2026-10-10 手机多线程 benchmark 原始对比

同一 Android 设备 Mali-G610 r0p0，`KATAGO_BENCH_VISITS=200`、`KATAGO_BENCH_POSITIONS=5`、`KATAGO_BENCH_THREADS=2,4,6,8`、19x19、10b 模型。以下百分比是 GPU 相对于**同线程数** CPU 的 visits/s 变化：

| 搜索线程 | CPU visits/s | GPU visits/s | GPU 相对 CPU | CPU nnEvals/s | GPU nnEvals/s | GPU avgBatchSize |
|---:|---:|---:|---:|---:|---:|---:|
| 2 | 44.64 | 42.80 | -4.1% | 39.62 | 40.20 | 1.00 |
| 4 | 78.55 | 77.01 | -2.0% | 71.51 | 68.44 | 1.98 |
| 6 | 80.34 | 100.71 | +25.4% | 75.17 | 93.72 | 3.01 |
| 8 | 79.33 | 115.91 | +46.1% | 75.04 | 109.41 | 3.97 |

- CPU 在 4–8 线程左右速度进入平台，GPU 随 batch≈1→4 继续提升；GPU 8 线程相比 CPU **最佳 6 线程**（80.34 visits/s）快约 44.3%。
- GPU 通过调优缓存快速启动；仍为 `FP16Storage=true`、`FP16Compute=true`、WMMA 禁用。
- benchmark 中的 `EloDiff` 是启发式估算，不是 CPU/GPU 实测等级分；不同后端/线程可能有不同的蒙特卡洛波动，数据需要重复、交叉顺序和温控验证。



### 2026-10-10 RK3588 固定访问量测试及中断修复

`--threads 2,4,8,16,24,32 --visits 20 --max-time 60`：CPU 2/4/8 各完成 20 次落子；每次报告 root visits 为 21（初始化根节点 + 搜索计数），平均 GTP 延迟分别约 **564.23ms / 577.99ms / 611.10ms**，短搜索配置的并行线程越多不一定越快。CPU 16 线程测试尚未产出有效行，因为在预热阶段 `read_root_visits` 未及时读取 stderr 诊断输出，脚本误将非关键统计信息缺失视为致命错误。

- [x] 更新 `tools/benchmark_katago_genmove_android.py`：轮询等待 ADB stderr 输出最多 1.5 秒，仍未收到 `MALKOVICH:Visits` 时将 `root_visits` 记为缺失（N/A），**保留有效落子耗时**并继续测试；缺失 visit 不参与 visits 平均值。
- [x] 新增 `--resume --report-dir`，读取已保存 `moves.csv`、跳过完成的配置与局面，剩余测完后生成完整 summary。续跑必须使用与原始实验相同的 `--threads`、`--visits`、`--max-time` 等配置；不自动核验跨运行的模型/参数一致性。
- [x] RK3588 已通过 `--resume` 从原有 60 步继续完成 CPU16/24/32 及全部 GPU（合计 240 步）固定 20-visits 测试。实际每步 root visits 为 21，GPU 最快 4 线程均值 536.29ms；CPU 最快 2 线程 564.23ms。此前 stderr 日志读取中断的问题未再出现。

### 2026-10-10 RK3588 固定访问量测试：完整结果

运行 `--threads 2,4,8,16,24,32 --visits 20 --max-time 60`，中断后 `--resume --report-dir build/katago_genmove/20261010_101718` 已恢复并完成；20 步×6 线程×CPU/GPU=240 步（每组 20 步）。每次 `MALKOVICH:Visits` 返回 21，20 visits 预算完整执行，root visits 包含额外的根节点计数差异。以下是 **GTP 命令往返平均延迟**，不是 APP 全流程帧耗时。

| 搜索线程 | CPU 平均延迟(ms) | GPU 平均延迟(ms) | GPU 同线程延迟降低 |
|---:|---:|---:|---:|
| 2 | 564.23 | 546.02 | 3.2% |
| 4 | 577.98 | 536.29 | 7.2% |
| 8 | 611.10 | 546.28 | 10.6% |
| 16 | 593.84 | 539.93 | 9.1% |
| 24 | 594.83 | 545.61 | 8.3% |
| 32 | 607.44 | 542.37 | 10.7% |

- CPU 最快 2 线程（564.23ms）；GPU 最快 4 线程（536.29ms），跨后端最优值之比 GPU 减少约 5.0% 延迟。GPU 4/16/32 平均值相差仅 6.08ms，需多轮、随机化测试顺序才可能稳定区分。
- 对比另一组 `--visits 1000 --max-time 0.4` 的结果，GPU 16/24/32 搜索吞吐明显优于 CPU；**高吞吐的最佳线程数不等于 20-visits 短搜索的最低延迟线程数**。
- 测试前每步执行 `clear_board`、`clear_cache`，因此模拟的是冷搜索而非 APP 连续对弈保留缓存的实际体验。CPU→GPU 固定顺序也可能有温度/负载偏差。
- [x] 已在 RK3588 和 vivo 上核验 `readelf -d/-V`、`SONAME`、`DT_NEEDED`、符号版本，且用同一份 `openclportable` ARM64 PIE 程序完成双设备 ADB Shell GPU 推理；Android APP 的 linker namespace / SELinux 访问验证仍待完成。

### 2026-10-10 RK3588 GTP 固定时间测试（20 次/配置）

真机序列号 `8719e18a71a2a66c`；`--threads 2,4,8,16,24,32 --visits 1000 --max-time 0.4`；19x19、10b 模型、10 个固定局面各重复 2 次。实际搜索 visits 均低于 1000，确认访问上限未触发。脚本已能通过 GTP 的 `MALKOVICH:Visits` 记录每次实际 root visits。

| 线程 | CPU 平均 visits | GPU 平均 visits | CPU 平均落子 ms | GPU 平均落子 ms |
|---:|---:|---:|---:|---:|
| 2 | 15.8 | 17.3 | 433.55 | 440.30 |
| 4 | 25.1 | 28.2 | 452.18 | 445.98 |
| 8 | 29.4 | 46.7 | 482.52 | 458.05 |
| 16 | 33.1 | 63.1 | 550.27 | 477.83 |
| 24 | 40.0 | 72.0 | 693.89 | 504.19 |
| 32 | 46.8 | 85.3 | 869.68 | 521.94 |

- GPU 16 线程为当前短时响应/搜索量的优先候选：平均 63.1 visits，477.83ms。GPU 32 线程 visits 更多但约 522ms；CPU 32 线程退化至约 870ms。
- **`maxTime=0.4` 不是 GTP 返回时长的硬截止**，尤其并发搜索时存在尾部等待与结束处理。不要只用平均 visits 或只用延迟一项判断强弱；过多线程也可能降低 MCTS 搜索质量。
- [x] 固定访问量 `--visits 20 --max-time 60` 的 CPU/GPU 完整配对实验已完成，见上一节 240 步测量；APP 默认线程数仍待端到端验证。
- [x] 旧的 `libGLES_mali.so` + `OPENCL_1.0` 固定依赖已通过独立 `openclportable` ARM64 PIE 解决，vivo 与 RK3588 ADB Shell 实际 GPU 推理均通过；后续还须验证真正 JNI 库。

### 2026-10-10 RK3588 与 vivo OpenCL ELF ABI 对比及实验构建

诊断文件来自两台 ARM64 Android 设备，证明故障首先发生在 ELF 动态链接阶段：

- RK3588 LubanCat-4IO（Android 12）：`/vendor/lib64/libOpenCL.so` 约 42MB、内部 SONAME=`libGLES_mali.so`，导出 `OPENCL_1.0`～`OPENCL_3.0` 符号版本。旧 GPU 可执行文件 `DT_NEEDED libGLES_mali.so`，且 `VERNEED OPENCL_1.0`。
- vivo V2502A（Android 16）：`/vendor/lib64/libOpenCL.so` 约 167KB、SONAME=`libOpenCL.so`，未显示 `OPENCL_1.0` 版本定义；`/vendor/lib64/egl/libGLES_mali.so` 是另一份约 53MB 的 Mali 驱动。
- 将手机 `libOpenCL.so` 简单重命名为 `libGLES_mali.so` 不能满足旧程序的 `DT_NEEDED` + `VERNEED`，这与手机 OpenCL 运算性能无关。

已添加 **隔离的 openclportable 试验模式**：`ANDROID_SERIAL=10AFB21HP5002ZK bash tools/build_katago_from_source.sh openclportable`，首次自动把该手机的 loader 库缓存为本地 **仅链接用** 的 `~/.cache/goengine_katago/opencl_arm64_portable/libOpenCL.so`（不进仓库、不打进 APK）。生成 `build/katago_android_arm64_openclportable/libkatago_exec_opencl.so`，不覆盖旧的 RK3588 版本，也不修改 CPU 或 APK。脚本核验新 ELF 只需求 `libOpenCL.so`，且没有 Mali 专属 OpenCL 版本依赖。

2026-10-10 vivo 手机 `openclportable` 已成功编译并运行：早期的 `clGetPlatformIDs` 检查误报（检测形式过于严格），修订后实际使用缓存的 166712 字节 `/vendor/lib64/libOpenCL.so` 成功链接；**未使用 53 MB EGL Mali 库回退**。NDK Clang 18 完成 `[117/117] Linking CXX executable katago`，新 ARM64 PIE ELF `DT_NEEDED libOpenCL.so`，无旧 RK3588 专有 `VERNEED OPENCL_1.0`。首次 GPU benchmark 在自动调优中途退出 255（原因未确认）；第二次自动调优完成并保存缓存，识别 **Mali-G1-Ultra MC12 r0p1 / OpenCL 3.0**，加载 10b 模型，GPU 2 搜索线程、19×19、30 visits ×1 position 获得 **66.00 visits/s、66.00 NN evals/s、batch 1.03**（样本很短，仅验证启动和基础计算）。对应 CPU/GPU 基准工具按符号版本依赖选择 RK3588 legacy staging 或设备 EGL Mali 库。随后未重新编译，直接将这份 **同一 ELF** 推送到 RK3588 LubanCat-4IO（Android 12）：识别 **Mali-G610 r0p0 / OpenCL 3.0**，复用其已存在调优缓存，FP16Storage/FP16Compute 为 true，10b 模型加载成功。GPU 2 搜索线程、19×19、30 visits ×1 position 获得 **39.66 visits/s、39.66 NN evals/s、batch 1.00**，退出状态正常。**跨两台设备的 ADB Shell GPU 推理兼容性已实测通过**；手机 66.00 vs RK3588 39.66 visits/s 只是各 1 个局面的烟雾测试，不应当作正式 GPU 性能对比；Android APP 进程的 linker namespace / SELinux 仍须单独验证。

测试方式：使用 `KATAGO_BENCH_GPU_BINARY="$PWD/build/katago_android_arm64_openclportable/libkatago_exec_opencl.so"` 指定替代版，再分别指定 `ANDROID_SERIAL` 对手机、RK3588 跑 `bash tools/benchmark_katago_android.sh gpu`。GTP 真实落子脚本也支持 `--gpu-binary <path>`。**同一份 ARM64 PIE ELF 已在 vivo Android 16 和 RK3588 Android 12 的 ADB Shell 环境成功完成 GPU 推理**，APP linker namespace / SELinux 更须单独验证。

注意：这个实验先保留两个独立的 Android PIE 可执行文件，不代表阶段 3 的「同一个 JNI `.so` CPU/GPU 运行时切换」已经完成。Android NDK 不自带厂商 OpenCL 库；库是否对 APP 可见还需要设备侧验证。

### JNI 改造前置验证：Android APP 内的 OpenCL 动态加载（2026-10-10）

- [x] 新增独立 `app/src/main/cpp/katago_probe.cpp` 和 Java `KataGoOpenCLProbe`，通过真正 JNI `libkatago_probe.so` 执行 `dlopen("libOpenCL.so")`、`dlsym("clGetPlatformIDs")`、平台数查询，输出清晰的加载错误。
- [x] `app/build.gradle.kts` 通过 `-PenableKataGoProbe=true` **可选**启用 `externalNativeBuild`。正常 APK 构建不触发新的 JNI 工程。
- [x] Manifest 添加 `<uses-native-library android:name="libOpenCL.so" android:required="false" />`（targetSdk 34；不支持 OpenCL 的设备仍能安装应用）。
- [x] `MainActivity` 只在 ADB intent 传入 `--ez katago_probe true` 时使用后台线程检查，日志 Tag `KataGoOpenCLProbe`；普通启动、不启用探针时原有 Java + PIE 对弈路径保持不变。
- [x] **vivo X300 Pro / Android 16 APP 进程实测通过**：已用 `-PenableKataGoProbe=true` 构建、安装并从 `MainActivity` 触发 JNI；2026-10-10 11:06:44 日志为 `KataGoOpenCLProbe: libOpenCL.so: loaded; clGetPlatformIDs error=0, platforms=1`。已证明 APP 内 `System.loadLibrary("katago_probe")`、`dlopen("libOpenCL.so")`、`dlsym("clGetPlatformIDs")`、查询 OpenCL 平台成功，但不意味着 APP 内完成完整模型推理。
- [x] **RK3588 / LubanCat-4IO / Android 12 APP 进程实测通过**：安装与 vivo 相同的 APK，ADB `am start ... --ez katago_probe true`；2026-10-10 11:08:23 日志：`KataGoOpenCLProbe: libOpenCL.so: loaded; clGetPlatformIDs error=0, platforms=1`。**两台设备 APP 内均成功通过 JNI 调用 OpenCL 平台枚举**。
- [ ] 下一个里程碑：CPU/Eigen 的真正 KataGo JNI `EngineSession`（加载模型/对弈/搜索/取消/释放），保留 `ProcessBuilder` 回退；现有探针仅验证 APP 加载库与 OpenCL 平台枚举，还没有执行 NN 运算或建立 GPU context。

探针验证：

```bash
./build.sh -PenableKataGoProbe=true
adb -s 10AFB21HP5002ZK install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 10AFB21HP5002ZK logcat -c
adb -s 10AFB21HP5002ZK shell am force-stop com.badukai.java
adb -s 10AFB21HP5002ZK shell am start -n com.badukai.java/com.badukai.MainActivity --ez katago_probe true
adb -s 10AFB21HP5002ZK logcat -d -s KataGoOpenCLProbe:I '*:S'
```

### 下一步实施顺序（2026-10-10 已完成双设备 APP OpenCL 探针后）

1. 原生核心拆分：`native/KataGo/cpp/CMakeLists.txt` 目前的 `add_executable(katago ... main.cpp)` 把引擎、GTP、工具命令一起链接成 PIE；需拆出可复用的核心目标，再单独建 JNI `SHARED` 目标，不能改文件名冒充 JNI。
2. GTP 会话拆分：`command/gtp.cpp` 当前为 `while(getline(cin,line))` 并依赖异步 `cout`，需要可传入命令、异步事件/结果回调的长生命周期 EngineSession。不能为每个命令重新执行一个 GTP main，也不能全局 `std::cin/std::cout` 重定向。
3. 先完成 CPU/Eigen 生命周期：Java `start(model, config)`、`sendCommand`、`stopSearch`、`destroy`，确认 `name`、`boardsize`、`play`、`genmove`、`undo`、分析；两套会话的全局状态需要核查。
4. CPU JNI 真机验证通过前，现有 `KataGoEngine.java` 的 `ProcessBuilder` 默认入口和 Gradle PIE 打包保持不变。CPU 迁移后再让 GPU OpenCL 后端进入同一个 JNI 主库；**现阶段未创建真正的 `libkatago.so`**。

### 2026-10-10 单设备优先验证与 JNI 链接修复

- 近期**只在 RK3588（ADB serial `8719e18a71a2a66c`）验证 CPU JNI、GTP 会话和 GPU JNI**；vivo 已验证 APP/OpenCL 能访问，暂不重复每次测试。RK3588 功能完成后再进行手机兼容回归。
- 用户 Ubuntu 首次运行 `bash tools/build_katago_from_source.sh eigenjni`：114/114 份源文件编译到了最终共享库链接步骤，但因缺少 `Version::getGitRevision`、`getKataGoVersion`、`getKataGoVersionForHelp`、`getKataGoVersionFullInfo` 而出现 `ld.lld: undefined symbol`，未生成通过验证的 `libkatago.so`。
- 根因已确认：上述 Version 实现原来写在 `native/KataGo/cpp/main.cpp` 内；启用 JNI 构建时必须排除 `main()`，却也排除了版本元数据实现。
- [x] 新建 `native/KataGo/cpp/version.cpp`、从 `main.cpp` 移出完整 Version 实现，并将 `version.cpp` 添加到 `KATAGO_SOURCE_FILES`；命令行 PIE 与 JNI 共享库共用版本实现，避免重复符号。
- [x] **RK3588 真机已确认 CPU JNI 核心库可加载并调用**：修复 Version 链接错误后，`eigenjni` 生成的 `libkatago.so` 已打包进 APK 并安装。2026-10-10 11:25:33，ADB `--ez katago_jni true` 日志：`KataGoJniCore: KataGo CPU/Eigen JNI core loaded; maxBoardSize=19`。这是 JNI 核心链接/调用验证，不是可下棋的 GTP JNI 引擎。

### 阶段 1A：独立 CPU/Eigen JNI ELF 构建起点（RK3588 已通过真机加载验证）

- [x] `native/KataGo/cpp/CMakeLists.txt` 将原 `add_executable(katago ... main.cpp)` 源码列表拆到 `KATAGO_SOURCE_FILES`；默认仍生成 `katago` PIE，只有 `-DKATAGO_BUILD_JNI_LIBRARY=ON` 才调用 `add_library(katago SHARED ... katago_jni.cpp)`，排除 `main.cpp`。
- [x] 新建 `app/src/main/cpp/katago_jni.cpp` 与 Java `KataGoNative`，首个 JNI 入口 `nativeBuildStatus()` 读取 KataGo `Board::MAX_LEN`，仅用于验证 C++ 引擎源码与 JNI 库成功链接/装载。
- [x] `bash tools/build_katago_from_source.sh eigenjni` 的独立输出为 `build/katago_android_arm64_eigenjni/libkatago.so`，CMake 使用 EIGEN CPU 后端，检查 ARM64 ET_DYN、SONAME 与 JNI 符号。
- [x] `./build.sh -PenableKataGoJniCore=true` 可选把预编译的 `libkatago.so` 与原有 `libkatago_exec.so` 一起打包；默认构建不包括实验 JNI 核心。通过 ADB intent `--ez katago_jni true` 触发日志 Tag `KataGoJniCore`，现有对弈仍使用 `ProcessBuilder`。
- [x] **Ubuntu 构建及 RK3588 Android APP JNI 加载验证通过**：11:25:33 打印 `KataGo CPU/Eigen JNI core loaded; maxBoardSize=19`。仅代表 JNI 库可装载、导出函数可调用；模型推理/GTP 仍未接入。
- [ ] 这一步只是 JNI 与原生源码的构建整合，`EngineSession`、GTP 请求/响应、模型加载和真实 CPU AI 落子**尚未实现**。不可把符号加载成功标记为整个 JNI 迁移完成。

验证命令：

```bash
git pull --ff-only github rk3588-engine
bash tools/build_katago_from_source.sh eigenjni
./build.sh -PenableKataGoJniCore=true
adb -s 10AFB21HP5002ZK install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 10AFB21HP5002ZK logcat -c
adb -s 10AFB21HP5002ZK shell am force-stop com.badukai.java
adb -s 10AFB21HP5002ZK shell am start -n com.badukai.java/com.badukai.MainActivity --ez katago_jni true
adb -s 10AFB21HP5002ZK logcat -d -s KataGoJniCore:I '*:S'
```

### 2026-10-10 RK3588 JNI GTP APP 重启循环定位与修复（真机验证通过）

- 用户 RK3588 试编通过 `eigenjni`（`[3/3] Linking CXX shared library libkatago.so`），Gradle `BUILD SUCCESSFUL`，APK 安装成功。
- `--ez katago_gtp true` 首次实测不断重启：11:39:44 起每隔约 0.23 秒生成新 PID，仅打印 `Starting JNI CPU/Eigen GTP session`。完整 Logcat 显示 `Zygote: Process ... exited cleanly (1)`，并无报告 native SIGSEGV/SIGABRT。
- **根因已在源码中确认**：`EngineSession.cpp` 组装 argv 为 `{"-model", model, "-config", config}`；`TCLAP::CmdLine::parse(vector)` 总是将 `args.front()` 当作程序名删掉，因而误把 `-model` 作为 argv0，随后遇到裸模型路径判定为未知参数；内置 TCLAP 默认 `exit(1)` 直接终止整个 Android APP。
- [x] 修复会话 argv 为 `{"gtp", "-model", model, "-config", config}`，对应原来 `main.cpp` 向 `MainCmds::gtp` 传入的参数结构。
- [x] JNI 注入流路径通过 `cmd.setExceptionHandling(false)` 禁止 TCLAP 的进程级 `exit(1)`，而原来命令行 `gtp(std::cin,std::cout)` 保持已有 TCLAP 处理方式。
- [x] 测试入口增加 `JNI session created` 和每条 `Sending ...` 的进度日志；测试脚本检测 3 次连续 APP 重启后提前报错而不是一直等待。
- [x] **RK3588 复测通过**：2026-10-10 11:47:44–11:47:45，单进程 PID 5340，JNI 载入 12003218 字节 10b 模型，成功返回 `name = KataGo`、`boardsize 9`、`komi 7.5`、`play B D4`、`genmove W = F6`、`undo`、`clear_board`，日志 `PASS: JNI GTP name/boardsize/komi/play/genmove/undo/clear_board`。说明 CPU/Eigen JNI 实际对弈推理成功，仍需生命周期和并发验证。

复测：
```bash
git pull --ff-only github rk3588-engine
bash tools/build_katago_from_source.sh eigenjni
./build.sh -PenableKataGoJniCore=true
bash tools/test_katago_jni_gtp_android.sh
```

### 阶段 1B：KataGo GTP JNI 单会话实验（RK3588 9×9 首次真机验证通过）

- [x] `native/KataGo/cpp/command/gtp_io.h` 新增 `MainCmds::gtpWithIO(args, input, output)`；`gtp.cpp` 将单一 `getline(cin, line)` 改为注入流，GTP 响应及异步分析输出改为会话持有的 `std::ostream`；原 `MainCmds::gtp` 仍用 CLI 标准输入输出。**没有重定向进程全局 `cin/cout`**。
- [x] `app/src/main/cpp/EngineSession.cpp` 加入线程安全输入队列、输出缓冲、一个本地 GTP 工作线程与按句柄管理的 JNI `createSession / sendCommand / readOutput / stopSearch / destroySession`；Java 包装 `KataGoNative.GtpSession implements AutoCloseable`。
- [x] 初期严格限制**单个 JNI GTP 会话**，避免上游 `ScoreValue::freeTables` / `NeuralNet::globalCleanup` 等进程级初始化清理在双会话间互相干扰。旧 `ProcessBuilder` 进程不受影响。
- [x] `MainActivity` 新增仅供 ADB 的 `--ez katago_gtp true` 测试入口，普通 APP 仍启动原引擎。调试入口自动准备单独模型/配置文件，顺序测试 `name`、`boardsize 9`、`komi 7.5`、`play B D4`、`genmove W`、`undo`、`clear_board`，Logcat 标签为 `KataGoJniGtp`。
- [x] **Ubuntu/Android NDK 构建与 RK3588 9×9 实际对弈通过**：`name → KataGo`，`play B D4 → =`，`genmove W → = F6`，并成功悔棋、清空棋盘（详见上方 2026-10-10 11:47 日志）。该测试只验一个 JNI 会话、一个 9×9 局面，未覆盖所有 APP UI 功能。
- [ ] `stopSearch` 当前仅把 GTP `stop` 命令排队：对同步 `genmove` 不能抢占；双会话并发、异步 Java 通知、长时间分析协议完整性、线程安全压力测试还没完成。不应据此切换 APP 默认引擎。
- [ ] JNI smoke 通过后再逐步对接 Java 原有 `KataGoEngine`，保留 `ProcessBuilder` 回退，并继续验证 Human SL、胜率分析和棋局恢复。

RK3588 验证（已有 `libkatago_exec.so` 默认路径保留）：

```bash
git pull --ff-only github rk3588-engine
bash tools/build_katago_from_source.sh eigenjni
./build.sh -PenableKataGoJniCore=true
adb -s 8719e18a71a2a66c install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 8719e18a71a2a66c logcat -c
adb -s 8719e18a71a2a66c shell am force-stop com.badukai.java
adb -s 8719e18a71a2a66c shell am start -n com.badukai.java/com.badukai.MainActivity --ez katago_gtp true
adb -s 8719e18a71a2a66c logcat -d -s KataGoJniGtp:I '*:S'
```

或者在编译完成后用新增的 `bash tools/test_katago_jni_gtp_android.sh` 一条命令完成安装、启动、等待 PASS/FAIL 并保存 `build/katago_jni_gtp_test/` 日志。

### 阶段 1C：JNI 会话生命周期 / 9×9、13×13、19×19 回归（RK3588 真机全部通过）

- [x] `MainActivity` 添加独立 `--ez katago_gtp_repeat true` 测试入口（不改变普通启动或原 9×9 smoke），在**同一个 Android 进程内**逐个创建/销毁 3 个 JNI 会话，分别对 9×9、13×13、19×19 调用 `name`、`boardsize`、`komi`、`play`、`genmove`、`undo`、`clear_board`。
- [x] `tools/test_katago_jni_gtp_android.sh` 增加第二参数 `repeat`，按模式传递 `katago_gtp_repeat` intent 参数，脚本只在全部三轮完成后接受 `PASS: JNI GTP repeated sessions 9x9, 13x13, 19x19`。
- [x] **RK3588 真机 2026-10-10 13:19:49–13:19:52 已验证通过**：同一 APP 进程 PID 5867，依次创建/释放三个独立 JNI GTP 会话；9×9 白棋 `genmove = F6`（281ms）、13×13 `K10`（337ms）、19×19 `D16`（416ms），每一轮 `name`、`boardsize`、`komi`、`play`、`genmove`、`undo`、`clear_board` 都得到成功响应，结束日志 `PASS: JNI GTP repeated sessions 9x9, 13x13, 19x19`。三个会话总耗时约 2.8 秒。仍未覆盖并发双会话、运行中断搜索及长期稳定性。
- [ ] 对长时间分析/停止/并发、Human SL、完整 APP 迁移暂不宣称通过。

RK3588 下一轮命令：

```bash
git pull --ff-only github rk3588-engine
./build.sh -PenableKataGoJniCore=true
bash tools/test_katago_jni_gtp_android.sh 8719e18a71a2a66c repeat
```

本次仅改 Java Smoke 与 ADB 脚本，没有更改 native C++，因此正常情况下可省略手动运行 `bash tools/build_katago_from_source.sh eigenjni`，继续使用上一轮已编译并通过验证的 `libkatago.so`。

### 2026-10-10 RK3588 APP JNI/Human SL 实测与 GTP 发送锁饥饿修复（已通过启动回归）

- [x] **用户 RK3588 11:59:58–12:00:39 主 APP 真机启动 JNI + 10b + Human SL 通过**：`Backend requested: JNI/Eigen`，`KataGo JNI GTP response: = KataGo`，`=== JNI/EIGEN ENGINE STARTED SUCCESSFULLY ===`。Human SL 文件 107185997 字节，10b 文件 12003218 字节。
- [x] 真机发现性能故障：`12:00:39.955 sendCommandSync in, command=boardsize 19` 到 `12:01:51.981 waitForResponse in` 相隔 **约 72 秒**，`boardsize 19` 返回 `=`，之后提交 `clear_board`。等待发生在 **调用 `waitForResponse` 之前**，不是 `waitForResponse(5000)` 超时。
- [x] 源码识别阻塞/饥饿风险：`KataGoNative.GtpSession.read(1000)` 和 `send()` 共用实例 `synchronized` Java monitor，GTP reader 在 native 阻塞读取时持续持锁并快速再次获取，可能使 GTP send 长时间抢不到锁。原生层已在输入队列、输出队列和 session registry 做锁保护；移除 Java `read/send/stopSearch` 的 `synchronized`，保留 `close()` 的幂等同步及 `volatile handle`。**经后续真机日志对照，发送延迟已降为 0ms；高度支持该锁饥饿诊断**。
- [x] `KataGoEngine.sendCommandSync` 加入 `queued, enqueueMs` 日志，`enqueueMs > 200` 触发 WARNING，方便区分发送阻塞与 KataGo 模型计算。
- [x] 修复潜在的首次模型加载超时：旧 JNI `waitForStartupResponse(30000)` 可能在移除 Java 发送锁后于模型真实加载约 41 秒期间提前失败；JNI 启动等待改为 120 秒（PIE 仍 30 秒），JNI 切换棋盘尺寸可能触发神经网络重建，`boardsize` 等待提高为 90 秒（PIE 仍 5 秒）。
- [x] **修复后 RK3588 正常 APP 初始化回归通过**：12:06:38.139–12:06:40.504，10b + Human SL，JNI `name` 2.34 秒返回，`boardsize 19`、`clear_board`、`kata-set-rules chinese`、`kata-get-rules`、`komi 7.5` 均成功；`Chinese rules verified=true`；`Initial engine ready=true humanSL=true elapsedMs=2470`。`boardsize 19` 的 `enqueueMs=0`，相比上次 72 秒发送卡顿已消失。
- [x] **RK3588 APP 9×9 实际新局部分功能通过（2026-10-10 12:09:53–12:09:54）**：主 JNI/Eigen `genmove white → G5`，Java 日志 `elapsedMs=856`；独立 PIE 胜率分析进程接收 `play white G5` 同步，随后 `kata-search_analyze black ... rootInfo true` 返回 `rootVisits=49`、黑胜率约 36.9%、白胜率约 63.1%；JNI `kata-raw-nn 0` 形势判断返回 `whiteWin=0.594147`、`whiteLead=1.007`，`evaluatePosition size=9 elapsedMs=45`，Java 输出 ownership estimate。说明主 JNI 实际 AI 落子、独立 PIE 胜率评估、JNI 形势判断的日志路径均工作。
- [ ] 仍需核实 **Human SL 等级 `rank_XXk` 的回读设置、悔棋、新局重建、长时间对弈、13×13/19×19 UI 与退出后重新创建**；尚不能宣布全部 UI 回归完成。

复测仅改 Java，沿用已构建的 `libkatago.so`：

```bash
git pull --ff-only github rk3588-engine
./build.sh -PenableKataGoJniCore=true
adb -s 8719e18a71a2a66c install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 8719e18a71a2a66c logcat -c
adb -s 8719e18a71a2a66c shell am force-stop com.badukai.java
adb -s 8719e18a71a2a66c shell am start -n com.badukai.java/com.badukai.MainActivity
adb -s 8719e18a71a2a66c logcat -d -s KataGoEngine:V MainActivity:I '*:S' | tail -100
```

### 阶段 1D：主 APP 对弈接入真正 JNI，保留 PIE 回退（RK3588 正常启动验证通过，完整对弈待测）

- [x] `KataGoEngine.java` 原有 GTP 高层接口保持不变，主引擎 `engine` 在 APK 含 `libkatago.so` 时**优先 JNI/Eigen**，共享 `responseQueue` 及 GTP 解析；通过 JNI `send` 和独立 reader thread 按 `\n\n` 解析 GTP 应答，不再依赖 `ProcessBuilder` 运行主对弈。未打包 `libkatago.so` 时保持原 PIE 行为。
- [x] 原生 `EngineSession.cpp` 与 Java `KataGoNative.java` 扩展 `createSession(model, config, humanModel)`，支持 `-human-model` 参数。主界面新局需 Human SL（`engine.start(Model.HUMAN,true)`），因此不能只接 10b 模型；JNI 使用原来的 `human_gtp.cfg` + Human SL 模型路径、动态 `humanSLProfile` 指令。
- [x] 对 JNI 配置的 `logDir` 和 `homeDataDir` 写入 APP 私有绝对路径，防止 JNI 没有 PIE 进程的工作目录时使用相对路径失败。
- [x] Java `isReady()` 与启动等待检查原生工作线程存活，JNI `genmove` 不再依赖旧 PIE `genmove_debug` 的 stderr 统计，避免无谓等待；已有围棋 UI `play`、`undo`、`setHumanRank`、`kata-raw-nn` 等仍通过统一 Java/GTP 协议调用。
- [x] JNI **启动失败**时（链接错误、native create 失败、启动 30 秒内收不到合法 `name`）禁用本 APP 进程中的 JNI 尝试并自动重试旧 `ProcessBuilder`；不能捕获/回退已发生的 native SIGSEGV/SIGABRT。
- [x] 独立的 `engine_winrate` **保持 PIE**，因为 JNI 原生层只允许一个会话且 KataGo 进程级全局清理/初始化还未隔离；不删除 `libkatago_exec.so`。
- [x] `MainActivity` 支持启动参数 `--ez katago_legacy true`，强制主引擎用 PIE（便于 APP JNI 真机故障时回退）。正常启动仅在打包 `libkatago.so` 时优先使用 JNI。
- [x] **用户已完成 Ubuntu 构建及 RK3588 普通 APP 的 JNI + Human SL 初始化验证**：2026-10-10 12:06:40 日志 `Initial engine ready=true humanSL=true elapsedMs=2470`；GTP 初始棋盘、规则和贴目设置成功。
- [x] **已收到 RK3588 9×9 Human SL 主 JNI 实际落子与独立 PIE 胜率分析日志**：12:09:53 主 JNI 白棋落 `G5`（856ms）；胜率 PIE 同步棋步并报告 black=0.369、white=0.631；JNI `kata-raw-nn` 形势结果可用（45ms）。
- [ ] 全 UI 测试仍不完整：Human SL 分级设置、UI 悔棋、重开局待实测。**阶段 1C 的 9/13/19 多次 JNI 创建/销毁已经真机通过**。
- [ ] 还需测试 Human SL 下载/模型启动/棋力切换、普通对局与独立胜率 PIE 并行、悔棋/重开局、形势判断/胜率分析、退出及反复加载。同步 `genmove` 中断、原生 fatal crash 恢复以及 JNI 双会话仍未解决。

第一次 RK3588 APP 迁移测试：

```bash
git pull --ff-only github rk3588-engine
bash tools/build_katago_from_source.sh eigenjni
./build.sh -PenableKataGoJniCore=true
adb -s 8719e18a71a2a66c install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 8719e18a71a2a66c logcat -c
adb -s 8719e18a71a2a66c shell am force-stop com.badukai.java
adb -s 8719e18a71a2a66c shell am start -n com.badukai.java/com.badukai.MainActivity
adb -s 8719e18a71a2a66c logcat -d -s KataGoEngine:I MainActivity:I '*:S'
```

强制 PIE 救援：`adb -s 8719e18a71a2a66c shell am force-stop com.badukai.java`，然后 `adb -s 8719e18a71a2a66c shell am start -n com.badukai.java/com.badukai.MainActivity --ez katago_legacy true`。

## 阶段 1：CPU/Eigen 真正 JNI 化 —— 首先实现

**目标**：构建可加载的 `libkatago.so`，Java 在 APP 进程内调用 KataGo；先不接 GPU/NPU。

- [ ] CMake 拆出可复用 KataGo 核心目标，增加 Android `SHARED` JNI 库目标，不把 `main()` 链入 JNI 库。
- [ ] 从 `command/gtp.cpp` 中抽离绑定 `std::cin/std::cout` 的 GTP 循环，建立可创建、可关闭的 `EngineSession` 和命令处理接口；禁止用修改全局 `cin/cout` 缓冲区来伪装 JNI。
- [ ] 在 Android 标准源码目录 `app/src/main/cpp/` 新增 `CMakeLists.txt`、`katago_jni.cpp`、`EngineSession.cpp` 与 Java `KataGoNative`；接口需能创建/销毁句柄、提交 GTP 命令、异步回传结果和错误、取消搜索。
- [ ] 明确 JNI 线程模型、回调附着/释放、超时、搜索线程关闭顺序；防止阻塞主线程和释放后回调。
- [ ] 保证 `engine` 与 `engine_winrate`（双进程模式）能够迁移为独立会话；检查 KataGo 进程级全局状态是否允许双实例，不可则先明确串行/共享限制。
- [ ] Gradle 接入 `externalNativeBuild` 或等效原生构建任务，APK 打包真正的 `libkatago.so`，保留现有可执行程序备用。
- [ ] 真机回归：装载库、启动、结束、重复创建/销毁、退出后不残留线程、9/13/19 路径、落子/悔棋、形势/胜率分析及 Human SL 相关流程。
- [ ] 以上验证通过后，将 Java 默认引擎切换至 JNI。

**阶段验收**：`System.loadLibrary("katago")` 可用，CPU 可实际对弈及分析；不再需要通过 `ProcessBuilder` 使用 KataGo（旧路径仍可作为回退）。

### 阶段 1E：GPU/OpenCL JNI 独立 ELF 构建（Ubuntu 编译及 ELF 检查通过）

- [x] 新增 `bash tools/build_katago_from_source.sh opencljni`：使用原 KataGo `KATAGO_BUILD_JNI_LIBRARY=ON` + `USE_BACKEND=OPENCL` 编译真正的 `libkatago.so`，复用已验证的 Android OpenCL 可移植链接库校验。
- [x] GPU JNI 构建输出独立于 CPU：`~/.cache/goengine_katago/build_android_arm64_opencljni/` 与 `build/katago_android_arm64_opencljni/libkatago.so`；不会覆盖 CPU `build/katago_android_arm64_eigenjni/libkatago.so`、现有 APK、`libkatago_exec.so`。
- [x] 检查 JNI ELF 的 AArch64/ET_DYN、`libkatago.so` SONAME、JNI 导出符号，GPU 依赖必须是非版本化 `libOpenCL.so`，拒绝 vendor-private `libGLES_mali.so` SONAME 以及依赖 `OPENCL_1.0` 等版本化符号。
- [x] **用户 Ubuntu 已编译成功 GPU JNI ELF**：2026-10-10 13:xx，`[119/119] Linking CXX shared library libkatago.so`、AArch64 `DYN (Shared object file)`、`SONAME libkatago.so`，`DT_NEEDED libOpenCL.so`（另有 libz/m/dl/c），没有打包厂商 GPU 库。`misc.cpp` 和 `writetrainingdata.cpp` 的 unused 变量警告不影响构建。
- [ ] **尚未在 RK3588 Android APP 加载 GPU JNI / 推理**。当前状态只验证二进制构建和 ELF 链接，不能声称 GPU APP 已经跑通。
- [x] 已新增**可选隔离的 GPU APP 真机 smoke**：`-PenableKataGoGpuJni=true` 将 GPU ELF 以 `libkatago_gpu.so` 添加到 APK（保留 CPU `libkatago.so`），`GpuSmokeActivity` 在 `android:process=":katago_gpu"` 的单独进程中先选 GPU 库再使用既有 `KataGoNative` JNI 导出，测试 `name`、`boardsize 19`、`komi`、`play`、`genmove`、`undo`、`clear_board`、`kata-raw-nn`。
- [x] `GpuSmokeActivity` 仅在启用构建属性时由 manifest 允许启动；正常 APP 仍是 CPU JNI + 胜率 PIE，旧 GPU ELF 只作为测试候选。独立进程避免两个静态编译的后端及 KataGo 全局初始化代码在同一 OS 进程内冲突；**这不是同一个 JNI 库内的运行时 GPU/CPU 切换**。
- [ ] 最终仍要实现**同一 JNI 主库的运行时 CPU/GPU 切换**，而不是长期保留两个编译时固定后端的 JNI 库。

初次构建（沿用已有缓存的可移植 ARM64 `libOpenCL.so`）：

```bash
git pull --ff-only github rk3588-engine
bash tools/build_katago_from_source.sh opencljni
readelf -d build/katago_android_arm64_opencljni/libkatago.so | grep -E 'NEEDED|SONAME'
```

### 阶段 1F：GPU JNI Android APP 内加载及真推理烟雾测试（RK3588 完整 PASS）

- [x] Gradle 可选打包 `libkatago_gpu.so`，源自已编译的独立 `build/katago_android_arm64_opencljni/libkatago.so`；只修改 ELF **APK 内文件名别名**，不修改 SONAME `libkatago.so`。依靠独立 Android 进程完成库隔离。
- [x] 原主进程的 `KataGoNative` 继续默认加载 CPU `libkatago.so`；GPU 活动先调用 `selectIsolatedGpuLibrary()`，仅在它自己的 `:katago_gpu` 进程加载别名 `libkatago_gpu.so`。正常 APP 不会加载或执行 GPU JNI。
- [x] GPU 活动仅通过 ADB 显式启动；测试真实 `genmove` 和 `kata-raw-nn`，日志标签 `KataGoGpuJni`；`tools/test_katago_gpu_jni_android.sh` 自动安装、启动、轮询 PASS/FAIL、保存日志并在失败时打印 crash buffer。
- [x] **2026-10-10 RK3588 Android APP 真机 GPU/OpenCL JNI 推理日志确认**：独立进程 PID 6008 在 13:31:05 完成 JNI 动态库加载并成功创建会话，GTP 会话继续执行到 `clear_board`、`kata-raw-nn 0`（返回 19×19 的 `whiteWin 0.541861`、`whiteLead 1.186`、完整 `policy` 和 `whiteOwnership`），随后 13:32:49 `Controller: quit`、`GPU -1 finishing, processed 19 rows 19 batches`、`All cleaned up, quitting`。因测试入口按顺序执行 `genmove W`、`undo`、`clear_board`、`kata-raw-nn 0` 且错误即停止，抵达 `kata-raw-nn 0` 可确定前序命令在该次测试中成功；**后续已收到完整 PASS 标记及命令响应，烟雾测试已通过**。这证实 APP 进程动态链接器可加载 GPU JNI/OpenCL，且 GPU 实际执行了 19×19 NN；整轮 13:31:05–13:32:49 约 104 秒，包含可能的首次调优，不能当作每步性能。
- [x] **完整成功日志**：2026-10-10 13:32:49.355 `Sending genmove W`，13:32:49.788 `genmove W -> = Q16`（约 433ms，接近配置中的 `maxTime=0.4` 搜索时间限制），13:32:49.810 `GPU OpenCL raw neural network inference OK`，13:32:49.896 `PASS: GPU JNI OpenCL GTP name/boardsize/play/genmove/undo/clear_board/kata-raw-nn`。验证了 Android APP 内独立 GPU JNI 真实落子、裸 NN 推理、GTP 和资源清理，不再只是 ELF/linker 验证。
- [x] **2026-10-10 第二次 RK3588 GPU JNI smoke PASS**：13:37:58.386 创建 GPU 会话，13:38:01.949 `name -> = KataGo`，JNI 初始化至 ready 约 **3.56s**；13:38:01.950 发出 `genmove W`、13:38:02.370 返回 `D16`（约 **420ms**）；13:38:02.373 发出 `kata-raw-nn 0`、13:38:02.393 返回白胜率 `0.541861`（约 **20ms**）；13:38:02.455 最终 `PASS`。完整运行约 **4.1s**，首次约 104s，对比缩短约 25 倍。**符合复用自动调优缓存的现象，但现有摘录没有明确缓存命中记录，不能仅凭时间推断已证实缓存读取**。
- [ ] 如需验证具体调优缓存命中，请检查 `files/jni_gpu_smoke/gtp_logs` 中的 OpenCL tuning 文件加载消息；GPU 420ms 接近 config 的 `maxTime 0.4`，不能用它对比 CPU 416ms 得出后端性能结论。
- [ ] 完整 UI CPU/GPU 后端选择、Human SL GPU 运行、并发/长时间稳定性，以及未来**同一 JNI 库**运行时 backend 切换仍待实现。

RK3588 测试命令（无须再编译 GPU C++，只要使用已生成的 ELF）：

```bash
git pull --ff-only github rk3588-engine
./build.sh -PenableKataGoJniCore=true -PenableKataGoGpuJni=true
bash tools/test_katago_gpu_jni_android.sh 8719e18a71a2a66c
```

正常 CPU APK 继续使用旧参数 `./build.sh -PenableKataGoJniCore=true`。GPU smoke 的独立 activity 在不启用 `-PenableKataGoGpuJni=true` 时由 manifest 禁用，不会影响正常主界面。

### 2026-10-10 GPU/OpenCL IPC GTP 响应分帧 Bug 修复（待 APP 真机复测）

- [x] 用户 14:10:17 的 RK3588 真机原生 GTP 日志显示，10b 及 Human SL 均已命中 OpenCL tuning cache；14:10:26 `Loaded human SL model` 和 `GTP ready`。APP 日志却始终缺少 `name` 回复，确认问题已从自动调优转移到 Java IPC 解析。
- [x] 找到 `KataGoEngine.startGpuReaderThread()` 的确定性错误：`buffer.indexOf("\\\\n\\\\n")` 在 Java 中搜索**字面反斜杠加 n**，而实际 GTP stdout 使用真实 `\\n\\n` 结尾。修正为与 CPU JNI reader 完全一致的真实双换行终止符，恢复 GPU IPC 应答拼包、分帧和 `responseQueue` 投递。提交 `42884d61`。
- [ ] 修复后需编译 APK 并实测 `KataGo GTP ready response: = KataGo`、`=== GPU/OPENCL JNI ENGINE STARTED SUCCESSFULLY ===`、`Game backend requested=GPU actual=GPU/OpenCL JNI ready=true`，然后测试真实 `genmove`、悔棋、形势判断以及切回 CPU。尚未声称 GPU APP 对弈已通过。

### 2026-10-10 RK3588 GPU + Human SL 首次 OpenCL 调优定位及超时修复

- [x] 14:00:59 APP 选择 GPU/OpenCL IPC 成功启动 `:katago_gpu` 进程并创建 GTP 会话，**不是 JNI 加载或 IPC 故障**。
- [x] 原生日志证实 10b 在 14:01:00 命中已有 `c128_mv8` 19×19 调优缓存并于 14:01:03 加载成功；Human SL `c384_mv15` 19×19 在 14:01:04 未找到有效缓存，触发 OpenCL autotuning。
- [x] 14:03:59 APP 180s 超时回退 CPU；**14:04:02 Human SL OpenCL autotuning 完成，参数保存到 `files/jni_gpu_smoke/home/opencltuning/tune11_gpuMaliG610r0p0_x19_y19_c384_mv15.txt`**；14:04:07 C++ 原生日志显示 10b + Human SL 双模型已加载且 `GTP ready`。时间上 GPU 只晚约 8s，不能误诊为 GPU Hang。
- [x] GPU 首次启动 `name` 等待由 180s 增至 360s；GPU 首次切换棋盘大小 `boardsize` 等待由 90s 增至 360s，以涵盖尺寸专属调优，**CPU JNI 的 120s 启动/90s boardsize 和 PIE 保持原样**。新局进入 GPU 时 UI 明示「首次调优可能需要数分钟」。
- [ ] **缓存保存后的第二次 19×19 GPU + Human SL 真实新局尚未回归**；预期会命中 c384 调优缓存而加快启动，但需要以新的 `Loaded tuning parameters`、`GPU/OPENCL JNI ENGINE STARTED SUCCESSFULLY`、`Game backend requested=GPU actual=GPU/OpenCL JNI ready=true` 真机日志确认为准。

### 阶段 1G：APP 新局 CPU / GPU 后端选择（代码已提交，待 RK3588 UI 实测）

- [x] 新局弹窗 `dialog_new_game.xml` 新增「AI 运行」下拉框：`CPU / Eigen`、`GPU / OpenCL`；没有打包 GPU JNI 的 APK 只显示 CPU，避免伪装可用。
- [x] `MainActivity` 通过 `SharedPreferences("katago_settings")` 记录本次新局选择，重开新局时应用它；对局 UI 显示引擎**实际后端**而不只是用户选择的标签。胜率分析仍保持独立 CPU PIE。
- [x] `GpuGtpService` 作为 `:katago_gpu` 进程内的 bound Service，使用 `Messenger` 接收命令、通过 IPC 分片返回完整 GTP stdout；`GpuRemoteSession` 在 APP 主进程接到既有 `responseQueue`，原来的棋局初始化、Human SL 棋力、落子、悔棋、形势判断和推荐搜索方法保持同一套调用。
- [x] 与已真机验证的 GPU smoke 共用 **独立 Android 进程/静态 OpenCL JNI 库**，主进程继续独占 CPU/Eigen JNI；不把同名 Eigen/OpenCL C++ 实现暴力链接进一个 JNI 库。
- [x] GPU 启动/IPC 失败时尝试自动回退 CPU JNI，再必要时回退 PIE；选项变更仅在新局前停止旧会话并启动新后端；GPU 运行过程中进程异常退出时快速结束等待并提示重新开局（**尚未支持当前棋局中途无缝迁移至 CPU**）。
- [x] 将已通过的 `jni_gpu_smoke/home` OpenCL 调优缓存优先用于 GPU 对局，尽量避免重新调优；若缓存不适用于棋盘尺寸或 Human SL 模型，仍可能首次调优。
- [ ] **待用户 Ubuntu 编译和 RK3588 真机验证**：进入新局选 GPU，确认 `GPU/OPENCL JNI ENGINE STARTED SUCCESSFULLY`、`Game backend requested=GPU actual=GPU/OpenCL JNI ready=true`，并测试 Human SL 等级切换、真实落子、悔棋、形势与胜率分析、切回 CPU 后能否重新启动。
- [ ] 完整阶段 2/3 所要求的单一 `libkatago.so` 内运行时 CPU/GPU/NPU 切换仍未实现；当前是两个 JNI ELF + 独立进程 IPC 的过渡架构。

构建和验证：

```bash
git pull --ff-only github rk3588-engine
./build.sh -PenableKataGoJniCore=true -PenableKataGoGpuJni=true
adb -s 8719e18a71a2a66c install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 8719e18a71a2a66c logcat -c
adb -s 8719e18a71a2a66c shell am force-stop com.badukai.java
adb -s 8719e18a71a2a66c shell am start -n com.badukai.java/com.badukai.MainActivity
adb -s 8719e18a71a2a66c logcat -d -s MainActivity:I KataGoEngine:I GpuGtpService:I GpuRemoteSession:I AndroidRuntime:E '*:S' | tail -100
```

## 阶段 2：抽象运行时 BackendManager —— 先只有 CPU

**目标**：今后增加计算后端不用改 Java/GTP/MCTS 核心协议。

- [ ] 定义 `BackendType { AUTO, CPU, GPU, NPU }`、统一的后端生命周期和推理接口，保留 KataGo 对模型元数据/批量输出/线程/缓冲区的实际需求。
- [ ] 将现有 `NeuralNet::...` 接口代理到后端实现，避免 Eigen/OpenCL 的 22 个同名函数直接链接冲突；处理 `LoadedModel`、`ComputeContext`、`ComputeHandle`、`InputBuffers` 的具体类型所有权。
- [ ] 改造 `program/setup.cpp` 与 `neuralnet/nneval.cpp`：将编译宏决定的布局、批大小、线程数、GPU 索引改成运行时后端属性。
- [ ] JNI 创建会话时接收后端枚举，返回**实际**启用的后端及错误原因；在 GPU/NPU 尚未实现时不假装支持。
- [ ] 对照阶段 1 CPU 推理和 GTP 功能回归，保持不变。

**阶段验收**：统一接口只接 CPU 也能完整使用，加入新后端不改应用层调用约定。

## 阶段 3：OpenCL GPU 加入同一个 `libkatago.so`

- [ ] 分离 `eigenbackend.cpp` 与 `openclbackend.cpp` 的同名实现，复用公共层并通过 BackendManager 分发。
- [ ] Android OpenCL 头文件、ARM64 链接/按需动态加载方案跑通；没有 OpenCL 的设备不能影响 CPU 模式加载。
- [ ] GPU 初始化、设备枚举、调优缓存、模型加载、推理及完整对弈验证。
- [ ] `AUTO` 优先尝试 GPU；初始化失败时正确清理，再以 CPU 重新建立会话（禁止半初始化对象复用）。
- [ ] vivo X300 Pro / RK3588 分别验证厂商 OpenCL 库可访问性、SELinux/linker namespace、正确性、速度、稳定性及内存占用。

**阶段验收**：同一个 JNI `libkatago.so` 可选 CPU 或 GPU；GPU 不可用时 CPU 正常可用。

## 阶段 4：Android 设置页与切换流程

- [ ] 提供 `自动 / CPU / GPU` 选择，显示实际生效后端和回退原因。
- [ ] 保存用户选择；切换后安全停止搜索、销毁旧会话、创建新会话并恢复棋局状态。
- [ ] 双引擎（对弈/胜率）切换与并发情况下验证稳定性。
- [ ] 等 NPU 后端完成之后再显示可用的 NPU 选项；未支持时不误导。

**阶段验收**：APP 中选择、重启引擎、恢复棋局可用，设置与实际后端一致。

## 阶段 5：RK3588 NPU / RKNN —— 最后实现

- [ ] 对 KataGo 的模型格式、网络算子、Policy/Value/Score/Ownership 输出及可变棋盘尺寸做转换可行性评估。
- [ ] 建立 `KataGo -> ONNX -> RKNN` 的可信转换链；必要时升级 KataGo 或编写模型导出工具。不能假定当前 10b.bin 可直接转换。
- [ ] 在 RK3588 的实际 Android/Linux 系统上验证 RKNN Runtime/驱动匹配、算子支持、模型输出和 CPU 参考结果的误差。
- [ ] 实现 `RknnBackend` 并接入统一接口；隔离 RKNN Runtime，使非 RK3588 设备依然可运行 CPU/GPU。
- [ ] 测试 `NPU` 以及 `AUTO` 策略，比较吞吐、功耗、延迟与实战落子稳定性。
- [ ] `*.rknn` 模型和设备专用运行库允许作为独立资源/依赖，**不强求全部静态嵌进单个 ELF**；主 JNI 库接口保持统一。

**阶段验收**：RK3588 上真实 NPU 推理并保持与 CPU 参考输出足够接近；其他手机不受 RKNN 依赖影响。

## JNI 目录规划（不立即移动现有可运行源码）

```text
GoEngine-java/
├── app/src/main/cpp/          # 新增：本项目 JNI、EngineSession、BackendManager 与后端适配层
│   ├── CMakeLists.txt
│   ├── katago_jni.cpp
│   ├── EngineSession.cpp
│   └── backend/
│       ├── BackendManager.cpp
│       ├── EigenBackend.cpp
│       ├── OpenCLBackend.cpp
│       └── RknnBackend.cpp   # 未来实现
└── native/KataGo/cpp/         # 保留：KataGo 上游 C++ 源码及必要本地补丁
```

- [ ] 在阶段 1 迁移时按此目录添加 JNI 源码，Gradle `externalNativeBuild.cmake.path` 指向 `app/src/main/cpp/CMakeLists.txt`。
- [ ] 新 CMake 显式引用 `native/KataGo/cpp` 的源码；注意上游 CMake 目前有 `add_executable(katago)`，不能原样 `add_subdirectory` 就得到 JNI 库。
- [ ] 原有 CPU 构建链及源文件路径保持不变，等 JNI 构建、打包和真机回归成功后再清理旧流程。

## 主要代码位置

- `native/KataGo/cpp/CMakeLists.txt` — 目前是 `add_executable(katago)`
- `native/KataGo/cpp/command/gtp.cpp` — 目前 `while(getline(cin,line))` 的 GTP 主循环
- `native/KataGo/cpp/neuralnet/nninterface.h` — 统一神经网络接口
- `native/KataGo/cpp/neuralnet/eigenbackend.cpp` — CPU/Eigen
- `native/KataGo/cpp/neuralnet/openclbackend.cpp` — GPU/OpenCL
- `native/KataGo/cpp/neuralnet/nneval.cpp`、`native/KataGo/cpp/program/setup.cpp` — 编译期后端相关逻辑
- `app/src/main/java/com/badukai/engine/KataGoEngine.java` — 现有 Java `ProcessBuilder` 调用
- `app/build.gradle.kts` — 现有 NDK/CMake/Gradle 构建打包逻辑

## 开发原则

1. **按阶段提交/测试/勾选**，不把 JNI、OpenCL 和 RKNN 三个大变更一次合并。
2. 现有 CPU APK 为回退基线；未通过实际编译和真机测试的阶段不可标记完成。
3. 一个真正的 JNI 主库（`libkatago.so`）是目标，CPU/GPU/NPU 运行时选择；**设备专用驱动和模型不要求全部嵌入单库**。
4. 高耗时 `genmove`、分析不能阻塞 Android UI 线程，JNI 内的 C++ 崩溃可能直接带崩 APP，应有错误处理与资源释放测试。
