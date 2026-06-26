#!/usr/bin/env bash
# release.sh — Create a GitHub release with auto-generated release notes
# Usage: ./scripts/release.sh [version]
#   version: e.g. "1.3.4" (defaults to reading from build.gradle.kts)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# --- Extract current version ---
VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  VERSION="$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\([^"]*\)".*/\1/')"
fi
TAG="v${VERSION}"

echo "=== DevCompanion Release: ${TAG} ==="

# --- Get previous tag for changelog ---
PREV_TAG="$(git tag -l 'v*' --sort=-version:refname | head -20 | grep -v "^${TAG}$" | head -1)"
if [ -z "$PREV_TAG" ]; then
  PREV_TAG="$(git rev-list --max-parents=0 HEAD)"
fi

echo "Previous tag: ${PREV_TAG}"

# --- Generate changelog from commits ---
CHANGELOG=""
while IFS= read -r line; do
  # Skip version bump commits from the changelog body (we'll mention version separately)
  if echo "$line" | grep -qE "^chore: bump version"; then
    continue
  fi
  CHANGELOG="${CHANGELOG}- ${line}"$'\n'
done < <(git log "${PREV_TAG}..HEAD" --oneline --no-decorate)

if [ -z "$CHANGELOG" ]; then
  CHANGELOG="- No notable changes since ${PREV_TAG}"$'\n'
fi

# --- Describe APK files ---
APK_DESCRIPTION=""
APK_DESCRIPTION="**APK 파일 안내:**
- \`app-arm64-v8a-release.apk\` — 대부분의 안드로이드 기기 (ARM64)
- \`app-x86_64-release.apk\` — 에뮬레이터 / x86_64 기기"

# --- Compose release notes ---
RELEASE_BODY="## ${TAG} 변경사항

${CHANGELOG}

${APK_DESCRIPTION}

---
**전체 변경 이력**: [${PREV_TAG}...${TAG}](https://github.com/JooyoungChoi14/DevCompanion/compare/${PREV_TAG}...${TAG})"

echo "--- Release Notes ---"
echo "$RELEASE_BODY"
echo "---"

# --- Create tag if not exists ---
if git tag -l "$TAG" | grep -q .; then
  echo "Tag ${TAG} already exists."
else
  echo "Creating tag ${TAG}..."
  git tag "$TAG"
  git push origin "$TAG"
  echo "Tag ${TAG} pushed."
fi

# --- Create or update GitHub release ---
if gh release view "$TAG" --json isDraft --jq '.' > /dev/null 2>&1; then
  echo "Release ${TAG} already exists. Updating notes..."
  gh release edit "$TAG" --notes "$RELEASE_BODY"
else
  echo "Creating release ${TAG}..."
  gh release create "$TAG" --title "${TAG}" --notes "$RELEASE_BODY"
fi

echo ""
echo "✅ Release ${TAG} published: https://github.com/JooyoungChoi14/DevCompanion/releases/tag/${TAG}"