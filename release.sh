#!/usr/bin/env bash
# 发版脚本（CI 版）：bump 校验 → commit → tag → push，
# 构建与 GitHub Release 由 .github/workflows/release.yml 接手（监听 v* tag）。
# Obtainium 靠 Release 检测更新；任何机器都不再需要 Android SDK。
# 用法: ./release.sh v0.1.2
# 注意: 先手动 bump app/build.gradle.kts 的 versionCode/versionName 再跑
set -euo pipefail
cd "$(dirname "$0")"

TAG="${1:?用法: ./release.sh <tag，如 v0.1.2>}"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "tag 格式应为 vX.Y.Z"; exit 1; }

VER="${TAG#v}"
grep -q "versionName = \"$VER\"" app/build.gradle.kts || { echo "app/build.gradle.kts 的 versionName 不是 $VER，请先 bump"; exit 1; }

git add -A
git commit -m "release $TAG" || true
git tag "$TAG"
git push origin main --tags

echo "已推送 tag $TAG，GitHub Actions 构建并发布 Release："
echo "https://github.com/tomasWade/dailynotes/actions/workflows/release.yml"
