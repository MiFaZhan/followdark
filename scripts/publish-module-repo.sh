#!/usr/bin/env bash
# 把模块推到 LSPosed 官方模块仓库 Xposed-Modules-Repo/com.mifazhan.followdark
# 前提：已通过 https://modules.lsposed.org/submission 提交并拿到该 org 下的仓库写权限。
set -euo pipefail

PKG=com.mifazhan.followdark
ORG_REPO="Xposed-Modules-Repo/$PKG"
VERSION_CODE=14
VERSION_NAME=2.6
TAG="$VERSION_CODE-$VERSION_NAME"
APK="build/followdark-v$VERSION_NAME.apk"

[ -f "$APK" ] || { echo "先跑 ./build.sh 生成 $APK"; exit 1; }

WORK=$(mktemp -d)
git clone "git@github.com:$ORG_REPO.git" "$WORK" 2>/dev/null \
  || gh repo clone "$ORG_REPO" "$WORK"
cd "$WORK"

cp "$OLDPWD/xposed/module.prop" ./module.prop
mkdir -p releases
cp "$OLDPWD/$APK" "./releases/$PKG-$VERSION_NAME.apk"

git add -A
git commit -m "chore: release $VERSION_NAME ($VERSION_CODE)" || echo "无变化"
git push

# 模块仓库要求：release 的 tag 必须是 VersionCode-VersionName，且带 apk 资产
gh release create "$TAG" "./releases/$PKG-$VERSION_NAME.apk" \
  --title "$VERSION_NAME" \
  --notes "见 https://github.com/MiFaZhan/followdark/releases/tag/$TAG"

rm -rf "$WORK"
echo "已发布到 https://github.com/$ORG_REPO (tag $TAG)"
