# KataGo Android ARM64 (Human SL 准备阶段)

目标：用同一份 Android ARM64 CPU/Eigen 引擎支持 RK3588 和 ARM64 手机，不引入任何厂商 NPU/GPU 依赖。

## 当前状态

- 现有 APK 内的 `app/src/main/jniLibs/arm64-v8a/libkatago_exec.so` 仍是已验证可用的旧版本，暂不覆盖。
- 本工具仅构建 **KataGo v1.15.0** 的 Android ARM64、API 26、Eigen CPU 版。
- v1.15.0 开始支持 `-human-model` 和 `humanSLProfile`；**当前 Java 启动参数尚未接入 Human SL，等级下拉框也还没改**。
- 构建出的二进制需要在 RK3588 和手机分别测试启动、对局，再切换默认版本。

## Ubuntu/WSL 本地编译

```bash
sudo apt-get update
sudo apt-get install -y cmake ninja-build libeigen3-dev
# 安装 Android SDK NDK，例如 r27c。替换成你机器的实际路径。
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/27.2.12479018"
cd GoEngine-java
bash tools/build_katago_android.sh
```

源代码按 `v1.15.0` 获取，编译中间文件放在 `~/.cache/goengine_katago/`，避免在 VMware 共享目录里反复编译大型 C++ 工程。

成功后，二进制生成于：

```
GoEngine-java/build/katago_android_arm64/libkatago_exec.so
```

该文件虽以 `.so` 结尾，实际是可执行的 Android arm64 PIE 程序，以便通过 Android 的 `nativeLibraryDir` 启动；不能将它当作 JNI 库 `System.loadLibrary()` 使用。脚本会检查 ELF 架构与 PIE 类型。

脚本默认**不会修改现有 APK 内的引擎**。只有当你明确要开始新版设备测试时，再运行：

```bash
bash tools/build_katago_android.sh --install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb logcat -s KataGoEngine:I KataGoEngine:W
```

`--install` 会先备份旧二进制到 `build/katago_android_arm64/libkatago_exec.previous.so`，再替换工程里的 `libkatago_exec.so`。先检查日志中的 KataGo 版本和 Engine STARTED，再测试普通对局、`kata-set-param` 的读回、`genmove_debug`。**未实测前不要发布新版 APK。**

## GitHub Actions

有一个手动/分支 push 触发的工作流：

`.github/workflows/katago_android_arm64.yml`

它使用 Android NDK r27c 编译并上传 `katago-1_15-android-arm64-eigen` 构建产物。此构建产物尚未自动装入 APK；下载安装和测试是单独的步骤。

## Human SL 下一步

模型使用 KataGo v1.15.0 官方发布的 `b18c384nbt-humanv0.bin.gz`（约 99MB）：

https://github.com/lightvector/KataGo/releases/tag/v1.15.0

新版启动时需要同时指定原有搜索模型 `-model` 和 Human SL 模型 `-human-model`，并使用适配的配置文件。等级例如 `rank_18k`、`rank_12k`、`rank_1k`。注意：Human SL 模仿棋风和等级，不是精确认证的实际段级位；我们会通过设备实测调整。

这一步不把大模型直接提交到 Git 仓库。后续加入按需下载或导入模型、校验以及启动失败时回退原有 10b 模型的机制。
