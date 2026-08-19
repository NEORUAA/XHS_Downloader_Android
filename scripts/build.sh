#!/bin/bash
# XHS Downloader Android - 构建脚本
# 用于在没有 Android Studio 打开的情况下进行命令行构建

set -e

# 配置环境变量
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

cd "$(dirname "$0")/.."

case "${1:-debug}" in
    debug)
        echo -e "${YELLOW}Building debug APK...${NC}"
        ./gradlew assembleDebug --no-daemon
        APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
        ;;
    release)
        echo -e "${YELLOW}Building release APK...${NC}"
        ./gradlew assembleRelease --no-daemon
        APK_PATH="app/build/outputs/apk/release/app-release.apk"
        ;;
    install)
        echo -e "${YELLOW}Building and installing debug APK...${NC}"
        ./gradlew installDebug --no-daemon
        exit 0
        ;;
    clean)
        echo -e "${YELLOW}Cleaning build...${NC}"
        ./gradlew clean --no-daemon
        exit 0
        ;;
    *)
        echo "Usage: $0 [debug|release|install|clean]"
        exit 1
        ;;
esac

if [[ -f "$APK_PATH" ]]; then
    echo -e "${GREEN}✓ Build successful!${NC}"
    echo "APK: $APK_PATH"
    ls -lh "$APK_PATH"
else
    echo "Build may have failed. Check output above."
    exit 1
fi
