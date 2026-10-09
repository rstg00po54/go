# KataGo 原生源码（直接存放在主仓库）

`GoEngine-java/native/KataGo/` 现在保存 **KataGo v1.15.0 官方 C++ 源码的真实文件**，不是 submodule，不需要运行 `git submodule update`。

- 上游：`https://github.com/lightvector/KataGo`
- 上游版本提交：`c560d38585b446bc93b0a62c77364bc8e7d266a4`
- 目录：`GoEngine-java/native/KataGo/cpp/`
- 已纳入：`core`、`search`、`game`、`neuralnet`、`command`、`dataio`、`external`、`book`、`program`、`distributed`、`configs`、CMake、编译需要的 28 个 `tests/*.cpp/.h`。
- 未纳入：上游的大型测试模型、测试结果与测试数据集（不影响目标 `katago` 可执行程序的 CMake 源码依赖）。

## 获取代码

```bash
git pull --ff-only origin rk3588-engine
ls GoEngine-java/native/KataGo/cpp/neuralnet/openclbackend.cpp
```

不需要其他 git clone 或 submodule 初始化。

## 编译 Android ARM64

```bash
cd GoEngine-java
bash tools/build_katago_from_source.sh eigen
```

输出 `build/katago_android_arm64_eigen/libkatago_exec.so`，依赖 Android NDK、CMake、Ninja、Eigen3。

RK3588 Android GPU 实验版：

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk
export OPENCL_INCLUDE_DIR=/path/to/OpenCL-Headers
export OPENCL_LIBRARY=/path/to/android-arm64/libOpenCL.so
bash tools/build_katago_from_source.sh opencl
```

输出 `build/katago_android_arm64_opencl/libkatago_exec_opencl.so`。OpenCL 头文件与 Android ARM64 链接库由你本地提供，**不提交设备厂商的 Mali 驱动**。需要在 RK3588 上测试 OpenCL 初始化和 GPU 调优，尚未验证编译通过。

## 原生文件的区别

- `jniLibs/arm64-v8a/libkatago_exec.so`：Android ARM64 PIE **可执行程序**，虽然文件名是 `.so`，但不是 JNI 接口。Java 通过 `ProcessBuilder` 启动并使用 GTP 交互。
- `jniLibs/arm64-v8a/libc++_shared.so`：NDK C++ 运行库，不是 KataGo 源码。源代码在 Android NDK/LLVM libc++ 项目。

新的编译脚本不会覆盖现有 CPU 二进制，且不会自动触发 APK 构建。

## 修改源代码

直接编辑 `native/KataGo/cpp/` 里的文件，与普通项目文件一样提交：

```bash
git add GoEngine-java/native/KataGo/cpp
git commit -m "Update KataGo OpenCL backend"
```

KataGo 许可证见 `native/KataGo/LICENSE`，第三方依赖的许可证仍保留在对应 `external` 子目录。
