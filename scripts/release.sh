#!/usr/bin/env bash
#
# release.sh — 构建并发布 ColorOS16 Liquid Glass
#
# 用法:
#   ./scripts/release.sh 0.1.0
#   ./scripts/release.sh 0.1.0 --notes "首个公开版本"
#   ./scripts/release.sh 0.1.0 --draft --prerelease
#   ./scripts/release.sh 0.1.0 --dry-run          # 只检查+构建，不发布
#   ./scripts/release.sh 0.1.0 --skip-build       # 复用已有 APK
#
# 说明:
#   - 版本号需与 app/build.gradle.kts 的 versionName 保持一致（脚本只提醒，不自动改）
#   - 默认构建 debug APK（release 未配置签名，产物无法直接安装）
#   - 需要已安装并登录 GitHub CLI (gh)
#
set -euo pipefail

# ---------------------------------------------------------------- 配置
REPO="AYwlilwYA/ColorOS16-LiquidGlass"
MAIN_BRANCH="main"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
GRADLE_TASK=":app:assembleDebug"

# ---------------------------------------------------------------- 参数
VERSION="${1:-}"
shift || true

NOTES=""
DRAFT=""
PRERELEASE=""
DRY_RUN=0
SKIP_BUILD=0

while [ $# -gt 0 ]; do
    case "$1" in
        --notes)      NOTES="${2:-}"; shift 2 ;;
        --draft)      DRAFT="--draft"; shift ;;
        --prerelease) PRERELEASE="--prerelease"; shift ;;
        --dry-run)    DRY_RUN=1; shift ;;
        --skip-build) SKIP_BUILD=1; shift ;;
        -h|--help)    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            echo "未知参数: $1" >&2; exit 1 ;;
    esac
done

if [ -z "$VERSION" ]; then
    echo "用法: $0 <版本号> [--notes 文本] [--draft] [--prerelease] [--dry-run] [--skip-build]" >&2
    exit 1
fi

TAG="v${VERSION}"
cd "$(dirname "$0")/.."

info() { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 环境检查
info "检查环境..."

command -v git >/dev/null || die "未找到 git"
command -v gh  >/dev/null || die "未找到 gh（GitHub CLI），请先安装并登录"

gh auth status >/dev/null 2>&1 || die "gh 未登录，请先执行: gh auth login"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" = "$MAIN_BRANCH" ] || die "当前在分支 '$BRANCH'，请切到 '$MAIN_BRANCH' 再发布"

if [ -n "$(git status --porcelain)" ]; then
    warn "工作区有未提交改动："
    git status --short | sed 's/^/    /'
    die "请先提交或 stash 后再发布"
fi

if git rev-parse "$TAG" >/dev/null 2>&1; then
    die "本地已存在 tag $TAG"
fi
if git ls-remote --exit-code --tags origin "$TAG" >/dev/null 2>&1; then
    die "远程已存在 tag $TAG"
fi

# 版本号一致性提醒（不阻塞）
DECLARED="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts 2>/dev/null || echo "")"
if [ -n "$DECLARED" ] && [ "$DECLARED" != "$VERSION" ]; then
    warn "app/build.gradle.kts 的 versionName 是 '$DECLARED'，与发布版本 '$VERSION' 不一致"
    warn "（不影响发布，但建议同步修改后再提交）"
fi

# ---------------------------------------------------------------- 构建
find_gradle() {
    if [ -x "./gradlew" ]; then
        echo "./gradlew"; return
    fi
    if command -v gradle >/dev/null 2>&1; then
        command -v gradle; return
    fi
    # 回退：本地 Gradle wrapper 缓存
    local d
    for d in "$HOME"/.gradle/wrapper/dists/gradle-*/*/gradle-*/bin/gradle; do
        [ -x "$d" ] && { echo "$d"; return; }
    done
    echo ""
}

if [ "$SKIP_BUILD" -eq 0 ]; then
    GRADLE="$(find_gradle)"
    [ -n "$GRADLE" ] || die "未找到 gradle（可执行 ./gradlew 或安装 gradle）"
    info "构建 APK（$GRADLE $GRADLE_TASK）..."
    "$GRADLE" -p . "$GRADLE_TASK"
else
    info "跳过构建（--skip-build）"
fi

[ -f "$APK_PATH" ] || die "未找到构建产物: $APK_PATH"

APK_SIZE="$(du -h "$APK_PATH" | cut -f1)"
info "产物: $APK_PATH ($APK_SIZE)"

# 发布时使用带版本号的文件名
APK_NAME="ColorOS16-LiquidGlass-${VERSION}.apk"

if [ "$DRY_RUN" -eq 1 ]; then
    info "dry-run：以下步骤未执行"
    echo "    git tag $TAG && git push origin $TAG"
    echo "    gh release create $TAG $APK_NAME --repo $REPO --title $TAG"
    exit 0
fi

# ---------------------------------------------------------------- 打 tag
info "创建并推送 tag $TAG ..."
git tag -a "$TAG" -m "Release $TAG"
git push origin "$TAG"

# ---------------------------------------------------------------- 发布
TMP_APK="$(mktemp -d)/$APK_NAME"
cp "$APK_PATH" "$TMP_APK"

info "创建 GitHub Release ..."
if [ -n "$NOTES" ]; then
    gh release create "$TAG" "$TMP_APK" \
        --repo "$REPO" \
        --title "$TAG" \
        --notes "$NOTES" \
        $DRAFT $PRERELEASE
else
    gh release create "$TAG" "$TMP_APK" \
        --repo "$REPO" \
        --title "$TAG" \
        --generate-notes \
        $DRAFT $PRERELEASE
fi

rm -rf "$(dirname "$TMP_APK")"

info "发布完成: https://github.com/$REPO/releases/tag/$TAG"
