# Human SL 18级～1级（Android ARM64）

## 工作方式

- KataGo **v1.15.0** Android ARM64 / Eigen CPU 引擎。
- 保留普通的 `10b.bin` 作为 KataGo 搜索与形势评估模型，并使用额外的官方 `b18c384nbt-humanv0.bin.gz` 作为模仿人类落子风格的 Human SL 模型。
- 新开一局选择 **18级～1级**：发送 `kata-set-param humanSLProfile rank_18k` 至 `rank_1k`；读取 `kata-get-param humanSLProfile` 确认参数生效。
- Human SL 配置以 KataGo 1.15.0 官方 `gtp_human5k_example.cfg` 为基础：`humanSLChosenMoveProp=1.0`，不人为延迟落子；`maxVisits=40`，最长搜索时间 `maxTime=8` 秒，不同 CPU 允许使用同样参数、按算力决定完成速度。
- 这些级位是 **模仿目标，不是经过比赛标定的实战等级认证**。

## 构建与模型获取

最简单：GitHub Actions 工作流 `Build Badukai Human SL APK` 会自动下载官方 99MB 模型、打包并生成离线可用的 APK；从该工作流的 Artifacts 下载 `BadukaiJ-HumanSL-arm64-debug-apk`。

本地原有流程仍支持：
```bash
cd GoEngine-java
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

本地 APK 不一定包含 99MB 模型。如果未包含，首次选择 18级～1级并点击“开始对局”时，App 会先征询同意，然后从以下官方地址通过 HTTPS 下载一次，放在 App 私有 `files/engine/` 下，以后离线复用：

https://github.com/lightvector/KataGo/releases/download/v1.15.0/b18c384nbt-humanv0.bin.gz

如果想在本地 APK 中直接离线打包，也可以提前把从上述地址下载的模型放在：
```
GoEngine-java/app/src/main/assets/engine/b18c384nbt-humanv0.bin.gz
```

如果 APK 中包含的是解压后的 `b18c384nbt-humanv0.bin`（107185997 字节），现在也可以直接使用；运行时会用官方解压文件的 SHA-256 校验。优先使用 gzip 格式，避免 APK 额外变大。

本地这个大模型文件已加入 `.gitignore`，不会误推送至 GitHub。

## 日志验证

```bash
adb logcat -c
adb logcat -s KataGoEngine:I MainActivity:E
```

进入新对局，选择例如 **12级**，应该看到：

```text
KataGo v1.15.0
Human SL model: /data/user/0/com.badukai.java/files/engine/b18c384nbt-humanv0.bin.gz
Human SL rank verified=true requested=rank_12k readback=rank_12k
genmove color=white move=... rootVisits=... newPlayouts=... elapsedMs=...
```

没有 Human SL 模型或 GTP 参数核对失败时，界面会报告失败，不会把现有普通 AI 冒充成所选等级。先保留原有对弈版本的 APK 安装包以便回退。

GitHub Actions 构建成功不等于真机验证通过；仍需在 RK3588 和 vivo X300 Pro 上分别验证模型初始化时间、首步耗时、内存占用及棋力表现。
