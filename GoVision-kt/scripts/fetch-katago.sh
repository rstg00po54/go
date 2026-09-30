#!/usr/bin/env bash
# 本地构建辅助：下载 KataGo Android(arm64-v8a) 二进制并放入 assets，
# 使本地构建出的 APK 同样具备“安装即用”体验。
#
# 用法：
#   ./scripts/fetch-katago.sh <url>
#   ./scripts/fetch-katago.sh            # 使用默认 URL（可按需替换为可信来源）
#
# 说明：KataGo 官方不发布 Android 预编译包，需自行编译或从可信社区获取。
# 该脚本仅做下载与放置，不对二进制来源做任何担保。

set -euo pipefail

URL="${1:-${KATAGO_BINARY_URL:-}}"
DEST="app/src/main/assets/engine/katago"

if [ -z "$URL" ]; then
  echo "未提供下载地址。" >&2
  echo "请通过参数或环境变量 KATAGO_BINARY_URL 指定 KataGo ARM64 二进制 URL。" >&2
  echo "示例: ./scripts/fetch-katago.sh https://your-host/katago-arm64" >&2
  exit 1
fi

mkdir -p "$(dirname "$DEST")"
echo "下载 $URL -> $DEST"
curl -fL --retry 3 -o "$DEST" "$URL"
chmod 755 "$DEST"
ls -lh "$DEST"
echo "完成。现在可执行 ./gradlew :app:assembleDebug 构建内置引擎的 APK。"
