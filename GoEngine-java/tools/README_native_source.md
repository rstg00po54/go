# KataGo 原生 C++ 源码（Android / RK3588）

本工程通过 **Git submodule** 固定 KataGo 官方 **v1.15.0** 源码：

- 本仓库路径：`GoEngine-java/native/KataGo/`
- 官方仓库：`https://github.com/lightvector/KataGo`
- 固定提交：`c560d38585b446bc93b0a62c77364bc8e7d266a4`
- C++ 搜索引擎、神经网络、OpenCL 实现：`native/KataGo/cpp/`

`jniLibs/arm64-v8a/libkatago_exec.so` 不是 JNI 接口库，而是以 `.so` 名称打包的 **Android arm64 PIE 可执行程序**，Java 使用 `ProcessBuilder` 启动，通过 GTP 与之通信。`libc++_shared.so` 是 Android NDK 的运行时库，源码属于 NDK/LLVM libc++，不是 KataGo 自己的实现。不复制/提交 Mali 厂商 GPU 驱动库。

## 拉取源代码

在仓库根目录：

```bash
git pull --ff-only origin rk3588-engine
git submodule update --init --recursive
```

若第一次克隆：`git clone --recurse-submodules -b rk3588-engine <仓库地址>`。

## CPU 版（仍保留现有 APK 引擎）

```bash
cd GoEngine-java
bash tools/build_katago_from_source.sh eigen
```

需要 Android NDK、CMake、Ninja、Eigen3。输出到 `build/katago_android_arm64_eigen/libkatago_exec.so`，不会替换 `app/src/main/jniLibs` 的可用二进制。

## RK3588 Android OpenCL 实验版

你的 RK3588 Android 已有 `/vendor/lib64/libOpenCL.so`，它链接到 `egl/libGLES_mali.so`，且 `/vendor/etc/public.libraries.txt` 列出了 `libOpenCL.so`。这意味着可进一步测试，但 **并不代表编译出来的程序必然能创建 OpenCL 设备**。

```bash
cd GoEngine-java
export ANDROID_NDK_HOME=/path/to/android-ndk
export OPENCL_INCLUDE_DIR=/path/to/OpenCL-Headers
export OPENCL_LIBRARY=/path/to/android-arm64/libOpenCL.so
bash tools/build_katago_from_source.sh opencl
```

`OPENCL_INCLUDE_DIR` 应包含 `CL/cl.h`；`OPENCL_LIBRARY` 是仅供交叉链接的 Android arm64 OpenCL 库，需保证最终 ELF 所需的 SONAME 可在目标设备被解析。**不要将厂商驱动或私有库上传至公开 Git 仓库**。

产物为 `build/katago_android_arm64_opencl/libkatago_exec_opencl.so`。它不会自动安装，也不会替换现有 CPU 引擎。首次上板应先检查 ELF 依赖、OpenCL 设备枚举、GPU 调优与运行日志，再决定是否集成到 APK。

注意：新增的源码与这个新脚本不会触发仓库现有按路径限定的自动编译工作流；不要改动或启动现有 GitHub Actions。

## 修改原生代码

可以在 `native/KataGo/cpp/` 阅读和本地修改 OpenCL 实现。由于这是 submodule，在内部做出的修改需要单独提交到可访问的 KataGo 分支／fork，再更新本仓库的 submodule 指针；或者把本地改动保存为主仓库的补丁文件。不能把子模块中的改动直接当作普通主仓库文件提交。

源码许可遵循 KataGo 上游许可证，参见 `native/KataGo/LICENSE`。
