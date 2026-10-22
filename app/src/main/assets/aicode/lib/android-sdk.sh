#!/bin/sh
# Shared Android SDK installation logic, used by scenarios/android.sh and scenarios/flutter.sh.
# See the "Advanced tutorial: Building Android apps in the container".

ANDROID_CLT_URL="https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip"
ANDROID_MIRROR="https://mirrors.cloud.tencent.com/AndroidSDK/"
ARM_TOOLS_URL="https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip"
ARM_TOOLS_PROXY_URL="https://gh-proxy.com/$ARM_TOOLS_URL"

# ── Detect the JDK's JAVA_HOME: prefer 17 (AGP / tutorial baseline), then 21; paths differ per distro, so try each candidate and also derive it from the java binary ──
java_home_dir() {
    for v in 17 21; do
        for d in /usr/lib/jvm/java-$v-openjdk-* /usr/lib/jvm/java-$v-* /usr/lib/jvm/openjdk-$v* /usr/lib/jvm/java-1.$v.0-*; do
            [ -x "$d/bin/java" ] && { echo "$d"; return 0; }
        done
    done
    if command -v java >/dev/null 2>&1; then
        jp=$(readlink -f "$(command -v java)" 2>/dev/null)
        case "$jp" in */bin/java) echo "${jp%/bin/java}"; return 0 ;; esac
    fi
    return 1
}

# ── JDK package to install under apt: prefer 17; fall back to 21 when the repo lacks it (Debian 13 / Ubuntu 26.04 only have 21)──
apt_jdk_pkg() {
    for p in openjdk-17-jdk-headless openjdk-21-jdk-headless; do
        if apt-cache policy "$p" 2>/dev/null | grep -q '^  Candidate: [0-9]'; then
            echo "$p"; return 0
        fi
    done
    echo "openjdk-17-jdk-headless"
}

# ── Install the Android SDK: cmdline-tools + sdkmanager components.
#    $1 = platform versions (space-separated, e.g. "36"); $2 = build-tools versions (space-separated, e.g. "33.0.1 35.0.0").
#    Components are fetched from the Tencent Cloud mirror (SDK_TEST_BASE_URL overrides sdkmanager's repository root). Idempotent: anything already installed is skipped.──
install_android_sdk() {
    platforms="$1"
    buildtools="$2"
    sdk="$HOME/android/sdk"
    mkdir -p "$sdk"
    if [ ! -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
        echo "Downloading Android commandline-tools..."
        (
            cd "$sdk" || exit 1
            rm -rf cmdline-tools-tmp
            curl -fL -C - -o clt.zip "$ANDROID_CLT_URL" || exit 1
            unzip -q -o clt.zip || exit 1
            mv cmdline-tools cmdline-tools-tmp || exit 1
            mkdir -p cmdline-tools/latest || exit 1
            mv cmdline-tools-tmp/* cmdline-tools/latest/ || exit 1
            rmdir cmdline-tools-tmp
            rm -f clt.zip
        ) || { echo "${C_RED}commandline-tools download/extraction failed.${C_RESET}"; return 1; }
    fi
    jh=$(java_home_dir) || { echo "${C_RED}JDK 17 not found; cannot continue.${C_RESET}"; return 1; }
    export JAVA_HOME="$jh"
    export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
    export PATH="$sdk/cmdline-tools/latest/bin:$sdk/platform-tools:$PATH"
    export SDK_TEST_BASE_URL="$ANDROID_MIRROR"
    args=""
    for p in $platforms; do args="$args \"platforms;android-$p\""; done
    for b in $buildtools; do args="$args \"build-tools;$b\""; done
    echo "Installing via sdkmanager: $args \"platform-tools\""
    eval "yes | sdkmanager --install $args \"platform-tools\"" || {
        unset SDK_TEST_BASE_URL
        echo "${C_RED}sdkmanager installation failed. Check the network and try again.${C_RESET}"
        return 1
    }
    unset SDK_TEST_BASE_URL
    return 0
}

# ── On aarch64, replace the native binaries inside the Android SDK (Google's aapt2/adb etc. are x86_64 and cannot run on ARM64).
#    Skipped in x86_64 containers (the official binaries work as-is). A failed download only warns and does not block the rest.──
install_android_tools_arm64() {
    [ "$(uname -m)" = "aarch64" ] || return 0
    sdk="$HOME/android/sdk"
    [ -d "$sdk/build-tools" ] || return 0
    zip="$HOME/.aicode-cache/android-sdk-tools-static-aarch64.zip"
    mkdir -p "$HOME/.aicode-cache"
    if [ ! -f "$zip" ]; then
        echo "Downloading ARM64 native binaries (aapt2 / adb, etc.)..."
        curl -fL -o "$zip" "$ARM_TOOLS_PROXY_URL" || curl -fL -o "$zip" "$ARM_TOOLS_URL" || {
            echo "${C_YELLOW}ARM64 binary download failed. You can retry in a new terminal (see the advanced tutorial).${C_RESET}"
            return 1
        }
    fi
    tmp="$HOME/.aicode-cache/armtools35"
    rm -rf "$tmp"
    mkdir -p "$tmp"
    unzip -q -o "$zip" -d "$tmp" || return 1
    for d in "$sdk"/build-tools/*/; do
        [ -d "$d" ] || continue
        cp -pf "$tmp"/build-tools/* "$d" 2>/dev/null
    done
    [ -d "$sdk/platform-tools" ] && cp -pf "$tmp"/platform-tools/* "$sdk/platform-tools/" 2>/dev/null
    chmod +x "$sdk"/build-tools/*/* "$sdk"/platform-tools/* 2>/dev/null
    echo "ARM64 native binaries replaced."
    return 0
}

# ── Write the Android environment variables to the container login shell and configure Gradle's aapt2 override (global; does not pollute projects)──
write_android_profile() {
    sdk="$HOME/android/sdk"
    jh=$(java_home_dir)
    mkdir -p /etc/profile.d
    {
        echo "# AiCode: Android / Kotlin development environment (written by the environment setup tool)"
        [ -n "$jh" ] && echo "export JAVA_HOME=\"$jh\""
        echo "export ANDROID_HOME=\"$sdk\""
        echo "export ANDROID_SDK_ROOT=\"$sdk\""
        echo "export PATH=\"$sdk/cmdline-tools/latest/bin:$sdk/platform-tools:\$PATH\""
    } > /etc/profile.d/aicode-android.sh
    if [ -x "$sdk/build-tools/35.0.0/aapt2" ]; then
        mkdir -p "$HOME/.gradle"
        gp="$HOME/.gradle/gradle.properties"
        if [ -f "$gp" ]; then
            grep -v "aapt2FromMavenOverride" "$gp" > "$gp.tmp" 2>/dev/null || true
        else
            : > "$gp.tmp"
        fi
        echo "android.aapt2FromMavenOverride=$sdk/build-tools/35.0.0/aapt2" >> "$gp.tmp"
        mv "$gp.tmp" "$gp"
    fi
    # Available immediately in the current session
    [ -n "$jh" ] && export JAVA_HOME="$jh"
    export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
    export PATH="$sdk/cmdline-tools/latest/bin:$sdk/platform-tools:$PATH"
}