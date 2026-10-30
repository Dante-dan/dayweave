#!/usr/bin/env bash
# 把 Google 服务账号凭证放进 App 私有目录（不打包进 APK、不进仓库）。
# 用法：android/scripts/push-credentials.sh [服务账号 JSON 路径]，默认读取仓库根目录 .env 的 GOOGLE_SERVICE_ACCOUNT_FILE。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SA="${1:-$(grep '^GOOGLE_SERVICE_ACCOUNT_FILE=' "$ROOT/.env" | cut -d= -f2- | sed "s#^~#$HOME#")}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
TMP=/data/local/tmp/dayweave-sa.json
"$ADB" push "$SA" "$TMP" >/dev/null
"$ADB" shell "run-as app.dayweave sh -c 'mkdir -p files && cat $TMP > files/google-sa.json && chmod 600 files/google-sa.json'"
"$ADB" shell rm -f "$TMP"
echo "credentials installed"
