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
- [ ] **待 RK3588 用户环境复测** `bash tools/build_katago_from_source.sh eigenjni`，成功后打包 `./build.sh -PenableKataGoJniCore=true`，再用 `--ez katago_jni true` 检查 APP 内 JNI 核心动态装载。暂不标记链接和 GTP JNI 功能完成。

### 阶段 1A：独立 CPU/Eigen JNI ELF 构建起点（代码已提交、真机验证未完成）

- [x] `native/KataGo/cpp/CMakeLists.txt` 将原 `add_executable(katago ... main.cpp)` 源码列表拆到 `KATAGO_SOURCE_FILES`；默认仍生成 `katago` PIE，只有 `-DKATAGO_BUILD_JNI_LIBRARY=ON` 才调用 `add_library(katago SHARED ... katago_jni.cpp)`，排除 `main.cpp`。
- [x] 新建 `app/src/main/cpp/katago_jni.cpp` 与 Java `KataGoNative`，首个 JNI 入口 `nativeBuildStatus()` 读取 KataGo `Board::MAX_LEN`，仅用于验证 C++ 引擎源码与 JNI 库成功链接/装载。
- [x] `bash tools/build_katago_from_source.sh eigenjni` 的独立输出为 `build/katago_android_arm64_eigenjni/libkatago.so`，CMake 使用 EIGEN CPU 后端，检查 ARM64 ET_DYN、SONAME 与 JNI 符号。
- [x] `./build.sh -PenableKataGoJniCore=true` 可选把预编译的 `libkatago.so` 与原有 `libkatago_exec.so` 一起打包；默认构建不包括实验 JNI 核心。通过 ADB intent `--ez katago_jni true` 触发日志 Tag `KataGoJniCore`，现有对弈仍使用 `ProcessBuilder`。
- [ ] **未在用户 Ubuntu 上编译验证 JNI ELF，也未在真机上加载验证。** 预期可能出现 CMake/链接/API 兼容错误，须由日志修复后才能继续。
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
