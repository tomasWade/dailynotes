#!/usr/bin/env bash
# 发版脚本：构建 → tag → GitHub Release（Obtainium 靠 release tag 检测更新）
# 用法: ./release.sh v0.1.2
# 注意: 先手动 bump app/build.gradle.kts 的 versionCode/versionName 再跑
set -euo pipefail
cd "$(dirname "$0")"

TAG="${1:?用法: ./release.sh <tag，如 v0.1.2>}"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "tag 格式应为 vX.Y.Z"; exit 1; }

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew :app:assembleDebug --console=plain -q

APK="dailynotes-$TAG.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$APK"

git add -A
git commit -m "release $TAG" || true
git tag "$TAG"
git push origin main --tags

gh release create "$TAG" "$APK" \
    --title "dailynotes $TAG" \
    --notes "自托管日记+速记。更新内容见 docs/development-plan.md。
服务端: server/docker-compose.yml（部署说明见 README）"

rm -f "$APK"
echo "已发布: https://github.com/tomasWade/dailynotes/releases/tag/$TAG"
