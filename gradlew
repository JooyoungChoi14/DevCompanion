#!/bin/bash
# ⚠️ LOCAL BUILD BLOCKED
# GeckoView dependency causes OOM crash on WSL (5GB RAM).
# Use GitHub Actions for builds instead.
# 
# To override: BUILD_FORCE=1 ./gradlew <command>
#
# GitHub Actions workflow: .github/workflows/android-build.yml

if [ "${BUILD_FORCE:-0}" != "1" ]; then
    echo "⛔ Local build blocked. WSL 5GB RAM causes OOM crash with GeckoView."
    echo "   Use GitHub Actions for builds, or set BUILD_FORCE=1 to override."
    echo "   Example: BUILD_FORCE=1 ./gradlew assembleDebug"
    exit 1
fi

# Strip BUILD_FORCE from args to avoid passing it to Gradle
export BUILD_FORCE=
exec "$(dirname "$0")/gradlew-real" "$@"