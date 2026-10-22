#!/bin/sh
# Scenario: Flutter development (Android SDK + Flutter SDK 3.27.4)
# Relies on the shared installation logic provided by lib/android-sdk.sh.
FLUTTER_VERSION="3.27.4"
FLUTTER_DIR="$HOME/.local/flutter-$FLUTTER_VERSION"
FLUTTER_STORAGE="https://storage.flutter-io.cn"

scenario_name_flutter() { echo "Flutter development (Flutter + Android SDK)"; }

install_scenario_flutter() {
    if [ "$PMGR" != "apt" ]; then
        echo "${C_YELLOW}Note${C_RESET}: For building Flutter apps, a Debian / Ubuntu image is recommended; the bundled Alpine (musl) needs extra handling."
        printf "The current package manager is %s. Continue anyway? [y/N]: " "${PMGR:-unknown}"
        read a
        case "$a" in y|Y) ;; *) return 2 ;; esac
    fi
    echo ""
    echo "Installing the JDK, git and base tools..."
    pkg_add "$(apt_jdk_pkg)" git curl wget unzip zip || return 1
    install_android_sdk "35" "33.0.1 35.0.0" || return 1
    install_android_tools_arm64 || true
    write_android_profile
    if [ ! -x "$FLUTTER_DIR/bin/flutter" ]; then
        echo "Cloning the Flutter SDK ($FLUTTER_VERSION)..."
        rm -rf "$FLUTTER_DIR"
        git clone --depth 1 --branch "$FLUTTER_VERSION" https://github.com/flutter/flutter.git "$FLUTTER_DIR" || {
            echo "${C_RED}Flutter clone failed. Try again or use a proxy.${C_RESET}"
            return 1
        }
    fi
    export PATH="$PATH:$FLUTTER_DIR/bin"
    export FLUTTER_STORAGE_BASE_URL="$FLUTTER_STORAGE"
    echo "Initializing Flutter (the first run downloads the dart-sdk; it may be slow, please wait)..."
    "$FLUTTER_DIR/bin/flutter" --version || echo "${C_YELLOW}Flutter initialization did not finish. Retry flutter --version in a new terminal.${C_RESET}"
    mkdir -p /etc/profile.d
    {
        echo "# AiCode: Flutter development environment (written by the environment setup tool)"
        echo "export FLUTTER_STORAGE_BASE_URL=\"$FLUTTER_STORAGE\""
        echo "export PATH=\"$FLUTTER_DIR/bin:\$PATH\""
    } > /etc/profile.d/aicode-flutter.sh
    echo ""
    echo "${C_GREEN}Flutter environment ready: $FLUTTER_DIR${C_RESET}"
    echo "  Note: ARM64 containers can only build debug APKs; new terminals automatically get the Flutter / Android environment variables."
    return 0
}