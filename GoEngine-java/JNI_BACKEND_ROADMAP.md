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
- [x] 提供同型号设备 CPU/GPU benchmark 脚本：`bash tools/benchmark_katago_android.sh gpu` / `cpu`。
- [x] 从 Android ARM64 设备读取 `/vendor/lib64/libOpenCL.so` 到本地缓存（约 42 MB），本机已找到 OpenCL 头文件；不提交供应商库。
- [x] 用户 Ubuntu + Android NDK 27.2.12479018 已完成 KataGo OpenCL AArch64 PIE 编译与链接（`[117/117] Linking CXX executable katago`），输出 `build/katago_android_arm64_opencl/libkatago_exec_opencl.so`。
- [x] Android 真机上的 GPU KataGo 已加载厂商 OpenCL Runtime，识别 Mali-G610 r0p0 / OpenCL 3.0，创建 OpenCL context；原先 `libGLES_mali.so` SONAME 找不到的问题已通过临时复制 vendor OpenCL 库解决。
- [ ] **完成 GPU 调优和实际 benchmark 验证**。已修复 `//.katago` 目录问题；Mali-G610 的 `xGemmDirect` 和 `xGemm` 调优能跑，但 `hGemmWmma` 报 `couldn't allocate output register for constraint 'r'`。已改 OpenCL 后端在 Mali 上跳过 WMMA，保留 FP16 普通计算及存储；待重编译和实机重试。
- [x] 同设备 19x19 / 10b / 100 visits / 2 搜索线程 / 2 positions CPU/Eigen 基线：`39.99 visits/s`、`38.60 nnEvals/s`、总测试约 5.1 秒（用户 2026-10-09 日志）。
- [ ] 运行相同模型、棋盘、visits、线程数及局面数的 GPU 测试，记录 GPU/CPU 对比与 GPU 首次调优耗时。
- [ ] 如果设备的 Android linker namespace/SELinux 不允许访问 OpenCL，记录实际错误；不能仅根据 OpenCL 库文件存在认定 APP 可用。
- [ ] 根据真实测试结果决定后续 JNI 双后端整合的优先级。

注意：这个实验先保留两个独立的 Android PIE 可执行文件，不代表阶段 3 的「同一个 JNI `.so` CPU/GPU 运行时切换」已经完成。Android NDK 不自带厂商 OpenCL 库；库是否对 APP 可见还需要设备侧验证。

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
