#!/bin/bash
# ⚠️ LOCAL BUILD GUIDE
# - `free` flavor: System WebView — can build locally (default)
# - `gecko` flavor: GeckoView — requires CI (221MB AAR, causes OOM on 5GB RAM WSL)
#
# To build locally:
#   ./gradlew assembleFreeDebug          # System WebView
#   ./gradlew assembleFreeRelease        # System WebView (release)
#
# For GeckoView builds, use GitHub Actions (push to feature/geckoview branch)
# or force with BUILD_FORCE=1:
#   BUILD_FORCE=1 ./gradlew assembleGeckoDebug  # ⚠️ May OOM on low-RAM systems

if [[ "${BUILD_FORCE:-0}" == "1" ]]; then
    export BUILD_FORCE=
    exec "$(dirname "$0")/gradlew-real" "$@"
fi

# Block gecko flavor builds locally (OOM risk)
for arg in "$@"; do
    case "$arg" in
        *Gecko*|*gecko*)
            echo "⛔ GeckoView builds require CI or BUILD_FORCE=1 (OOM risk on low-RAM systems)."
            echo "   Use: ./gradlew assembleFreeDebug for local builds."
            echo "   Or:  BUILD_FORCE=1 ./gradlew assembleGeckoDebug"
            exit 1
            ;;
    esac
done

# Allow free flavor and unspecified flavor builds
exec "$(dirname "$0")/gradlew-real" "$@"