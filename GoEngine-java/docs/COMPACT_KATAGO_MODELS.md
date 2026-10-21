# KataGo 紧凑模型 APK

## 目标

Human b18 与 10b 各自有 9、11、13、15、19 五种固定尺寸的
TFLite / RKNN 图。现在采用 19×19 原模型为基底，其他四个尺寸
保存无损 `.kgdelta` 补丁，Android 首次使用时还原成**原始逐字节一致**
的模型，不改变已有 CPU / GPU / MediaTek / RKNN 后端。

默认开启，可传 `-PcompactKataGoModels=false` 恢复完整模型打包。

## 使用

```bash
cd GoEngine-java
python3 tools/test_pack_katago_models.py
bash build.sh --vivo --apk-only
# 或：bash build.sh --rk3588 --apk-only

unzip -l app/build/outputs/apk/debug/app-debug.apk \
  | grep -E 'katago_(human|10b)_(9x9|11x11|13x13|15x15).*(tflite|rknn).kgdelta'
```

完整模型会先在 `app/build/generated/katagoCompactAssets` 中完成
差分构建、回放解码、SHA-256 与逐字节比较，然后才从该临时
Android assets 源目录移除重复模型。原始
`app/src/main/assets` 和下载的 RKNN 文件不会被删除。

- 格式：`KGMDEL01`，基底 SHA-256、目标 SHA-256、目标大小、
  COPY(源偏移、长度) 或 INSERT(原样数据) 指令；解码器见
  `KataGoModelDelta.java`。
- 模型加载：`KataGoEngine.copyAssetToFile()` 在 APK 中没有完整目标
  但有 `.kgdelta` 时解码为原文件名；原有 JNI 和 LiteRT 完全不知道
  差分的存在。
- 缓存：App 私有 files/engine* 中的模型，首次还原校验 SHA-256；
  同 APK 版本、大小和修改时间一致则复用缓存。首次进入新棋盘会多一次
  复制基础模型和还原的 I/O，后续同版本同棋盘不重复还原。
- 安卓升级 / 缓存失效后会重新校验或生成。每次仅解码所需模型，
  但是多种棋盘使用过后，手机存储中仍可能积累多份完整模型。

## 验证边界

仅生成 SHA-256 校验和逐字节相同的原始文件，才能被 MediaTek manifest
或 Rockchip release digest 接受。模型不是动态尺寸：切换棋盘仍需要按
原流程重新建立固定形状 NPU 会话。

本改动提交时未在真实 vivo/RK3588 上构建、安装或计时；
合并后的 APK 实际节省容量以及首次还原耗时需要实测确认。
若 `COMPACT_MODEL_*` 日志提示失败，请先执行
`bash build.sh --vivo --apk-only -PcompactKataGoModels=false`
恢复完整打包以排查。
