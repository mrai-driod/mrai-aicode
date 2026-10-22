#!/bin/sh
# Scenario: Kotlin / Android development (JDK 17 + Android SDK 36 + build-tools 35.0.0)
# Relies on the shared installation logic provided by lib/android-sdk.sh.
scenario_name_android() { echo "Kotlin / Android development (JDK + Android SDK)"; }

install_scenario_android() {
    if [ "$PMGR" != "apt" ]; then
        echo "${C_YELLOW}Note${C_RESET}: For building Android apps, a Debian / Ubuntu image is recommended; the bundled Alpine (musl) needs extra handling."
        printf "The current package manager is %s. Continue anyway? [y/N]: " "${PMGR:-unknown}"
        read a
        case "$a" in y|Y) ;; *) return 2 ;; esac
    fi
    echo ""
    echo "Installing the JDK and base tools..."
    pkg_add "$(apt_jdk_pkg)" curl wget unzip || return 1
    install_android_sdk "36" "35.0.0" || return 1
    install_android_tools_arm64 || true
    write_android_profile
    echo ""
    echo "${C_GREEN}Android / Kotlin environment ready: $HOME/android/sdk${C_RESET}"
    echo "  New terminals automatically get JAVA_HOME / ANDROID_HOME; for build steps, see the 'Advanced tutorial: Building Android apps in the container'."
    return 0
}