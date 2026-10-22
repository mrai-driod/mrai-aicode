#!/bin/sh
# Container environment script (generic): one script, two entry points.
#   1) First terminal entry: shows a setup menu on the PTY (Auto install / Environment setup / Manual install / Exit); when the user finishes,
#      it writes the /.provisioned marker, and the menu no longer appears on later entries.
#   2) Running the `aicode` command inside the container (equivalent to `provision.sh --env`): opens the Environment Setup menu directly,
#      to install a development environment by scenario; reusable any time, unaffected by the setup marker.
# Both entry points share the same logic: install runtimes via the container's package manager (apk/apt-get/dnf/yum/pacman), with optional China mirror sources.
# Extracted by the App at startup to ~/.aicode/provision.sh (inside the container: /root/.aicode/provision.sh, visible via a -b bind mount);
# shared capabilities (package-manager detection / package-name mapping / version detection / mirror switching / scenarios) live in lib/env-common.sh, extracted together with this script.
# After changing the package list / install logic / mirrors, also bump LinuxContainerEngine.PROVISION_VERSION by 1 so existing devices re-run provisioning.
# Note: the apk source branch v3.21 must stay in sync with the alpine-rootfs version in assets (ContainerInstaller.INSTALL_VERSION).

PROVISION_VERSION="provision-script-v10"
PROVISION_SKIPPED="provision-script-skipped"
MARKER="/.provisioned"

# ── Load the shared library (package-name mapping / mirror switching / scenarios, etc.). Located relative to this script's own directory: in production the script is at /root/.aicode/provision.sh and
# the library in /root/.aicode/lib/; the workspace draft copy (aicode-env/) has the same layout, so the same script runs in both places.
# If the library is missing, do not block: tell the user to restart the App so it re-extracts, then re-enter the terminal. ──
SELF_DIR=$(dirname "$0")
[ "${SELF_DIR#/}" = "$SELF_DIR" ] && SELF_DIR="$(pwd)/$SELF_DIR"
AICODE_LIB_DIR="$SELF_DIR/lib"
LIB_FILE="$AICODE_LIB_DIR/env-common.sh"
if [ ! -f "$LIB_FILE" ]; then
    AICODE_LIB_DIR="${HOME:-/root}/.aicode/lib"
    LIB_FILE="$AICODE_LIB_DIR/env-common.sh"
fi
if [ ! -f "$LIB_FILE" ]; then
    echo "Environment tools are not ready yet. Please restart the App and re-enter the terminal." >&2
    exit 0
fi
. "$LIB_FILE"

detect_pmgr

# ── List of installed runtimes (used by the environment setup menu) ──
list_installed() {
    echo ""
    echo "Installed runtimes:"
    for c in "node:Node.js" "python3:Python 3" "java:Java" "go:Go" "rustc:Rust" "php:PHP" "git:Git" "rg:ripgrep"; do
        cmd=${c%%:*}
        name=${c#*:}
        if command -v "$cmd" >/dev/null 2>&1; then
            ver=$("$cmd" --version 2>&1 | head -1)
            echo "  · $name: $ver"
        else
            echo "  · $name: (not installed)"
        fi
    done
    [ -x "$HOME/android/sdk/cmdline-tools/latest/bin/sdkmanager" ] && echo "  · Android SDK: $HOME/android/sdk"
    [ -x "$HOME/.local/flutter-$FLUTTER_VERSION/bin/flutter" ] && echo "  · Flutter SDK: $HOME/.local/flutter-$FLUTTER_VERSION"
}

# ── Environment setup menu (scenario-based): shared by the setup menu's Environment Setup entry and the `aicode` command ──
env_install_menu() {
    TOTAL=$(echo $SCENARIO_IDS | wc -w)
    CUSTOM_IDX=$((TOTAL + 1))
    MIRROR_IDX=$((TOTAL + 2))
    LIST_IDX=$((TOTAL + 3))
    EXIT_IDX=$((TOTAL + 4))
    while :; do
        echo ""
        echo "${C_CYAN}Environment Setup${C_RESET} — choose a development environment to install"
        echo "${C_YELLOW}──────────────────────────────${C_RESET}"
        i=1
        for id in $SCENARIO_IDS; do
            printf "  ${C_GREEN}%d.${C_RESET} %s\n" "$i" "$(scenario_name "$id")"
            i=$((i + 1))
        done
        printf "  ${C_GREEN}%d.${C_RESET} Custom install (pick items one by one)\n" "$CUSTOM_IDX"
        printf "  ${C_GREEN}%d.${C_RESET} Switch package mirror only\n" "$MIRROR_IDX"
        printf "  ${C_GREEN}%d.${C_RESET} View installed runtimes\n" "$LIST_IDX"
        printf "  ${C_DIM}%d. Exit${C_RESET}\n" "$EXIT_IDX"
        echo "${C_YELLOW}──────────────────────────────${C_RESET}"
        printf "Select: "
        if ! read choice; then
            echo ""
            break
        fi
        case "$choice" in
            ""|*[!0-9]*)
                echo "${C_RED}Invalid input, please select again.${C_RESET}"
                continue
                ;;
        esac
        if [ "$choice" -ge 1 ] && [ "$choice" -le "$TOTAL" ]; then
            id=$(echo $SCENARIO_IDS | awk -v i="$choice" '{print $i}')
            plog "Environment setup: scenario $id"
            install_scenario "$id"
            rc=$?
            case "$rc" in
                0) echo ""; echo "${C_GREEN}Installation complete.${C_RESET}" ;;
                2) echo ""; echo "Cancelled, back to the menu." ;;
                *) echo ""; echo "${C_RED}Installation failed. You can retry or switch to another mirror.${C_RESET}" ;;
            esac
        elif [ "$choice" -eq "$CUSTOM_IDX" ]; then
            plog "Environment setup: custom install"
            install_custom
            rc=$?
            case "$rc" in
                0) echo ""; echo "${C_GREEN}Installation complete.${C_RESET}" ;;
                2) echo ""; echo "Cancelled, back to the menu." ;;
                *) echo ""; echo "${C_RED}Installation failed. You can retry or switch to another mirror.${C_RESET}" ;;
            esac
        elif [ "$choice" -eq "$MIRROR_IDX" ]; then
            plog "Environment setup: mirror only"
            ask_mirror
            echo "Package sources updated."
        elif [ "$choice" -eq "$LIST_IDX" ]; then
            list_installed
        elif [ "$choice" -eq "$EXIT_IDX" ]; then
            plog "Environment setup: exit"
            break
        else
            echo "${C_RED}Invalid input, please select again.${C_RESET}"
        fi
    done
}

# ══ Entry point 2: `aicode` (provision.sh --env) → opens the environment setup menu directly, unaffected by the setup marker ══
if [ "$1" = "--env" ]; then
    plog "aicode opening the environment setup menu: PMGR=${PMGR:-unknown}"
    if [ -z "$PMGR" ]; then
        echo "${C_RED}Unrecognized package manager (apk/apt-get/dnf/yum/pacman); cannot install dependencies.${C_RESET}"
        exit 1
    fi
    env_install_menu
    exit 0
fi

# ══ Entry point 1: first-time terminal setup flow ══
plog "provision.sh started: version=$PROVISION_VERSION HOME=$HOME uid=$(id -u 2>/dev/null) PATH=$PATH"

# ── Host supplementary gid fix: proot passes the host process's supplementary groups (Android AID 3003 inet,
# 9997 everybody, a gid derived from the App's own uid, etc.) into the container; when /etc/group has no name for a gid,
# commands like groups complain (cannot find name for group ID xxx). Idempotent fix-up: clear the gid_ namespace first, then re-add,
# to avoid stale leftovers; this runs before the MARKER check on every terminal entry (already-configured devices included), and follows gid changes automatically.
# Everything fails silently and never blocks entering the shell.
sed -i '/^gid_/d' /etc/group 2>/dev/null
for g in $(id -G 2>/dev/null); do
    grep -q ":x:$g:" /etc/group 2>/dev/null || echo "gid_$g:x:$g:" >> /etc/group 2>/dev/null
done

# Exit immediately if setup already completed for the current version, or the user chose manual install (skipped)
if [ -f "$MARKER" ]; then
    state=$(cat "$MARKER" 2>/dev/null)
    if [ "$state" = "$PROVISION_VERSION" ]; then plog "Marker is already $PROVISION_VERSION, skipping the menu"; exit 0; fi
    if [ "$state" = "$PROVISION_SKIPPED" ]; then plog "User previously chose manual install, skipping the menu"; exit 0; fi
fi

plog "Showing setup menu: PMGR=${PMGR:-unknown}"

while :; do
    echo ""
    cat <<EOF
${C_CYAN} __  __ ____      _    ___      _    ___ ____ ___  ____  _____ ${C_RESET}
${C_CYAN}|  \/  |  _ \    / \  |_ _|    / \  |_ _/ ___/ _ \|  _ \| ____|${C_RESET}
${C_CYAN}| |\/| | |_) |  / _ \  | |    / _ \  | | |  | | | | | | |  _|  ${C_RESET}
${C_CYAN}| |  | |  _ <  / ___ \ | |   / ___ \ | | |__| |_| | |_| | |___ ${C_RESET}
${C_CYAN}|_|  |_|_| \_\/_/   \_\___| /_/   \_\___\____\___/|____/|_____|${C_RESET}
${C_YELLOW}═══════════════════════════════════════════════════════════════${C_RESET}
${C_BOLD}  Container Setup · Choose an Installation Method${C_RESET}
${C_YELLOW}══════════════════════════════════════════════${C_RESET}
  ${C_GREEN}1. Auto-install dependencies${C_RESET} (recommended)
  ${C_GREEN}2. Environment Setup${C_RESET} (choose a runtime environment by scenario)
  ${C_BOLD}3. Manual install${C_RESET} (don't ask again)
  ${C_DIM}4. Exit${C_RESET}
${C_YELLOW}══════════════════════════════════════════════${C_RESET}
${C_DIM}  Tip: run the aicode command in the terminal at any time to open Environment Setup${C_RESET}
EOF
    printf "Please choose: "
    if ! read choice; then
        echo ""
        echo "Input interrupted, exiting setup"
        break
    fi
    case "$choice" in
        1)
            plog "User chose 1 (auto install)"
            echo ""
            echo "${C_YELLOW}Install tips${C_RESET}:"
            echo "  · Installation takes a while; turn on background keep-alive and keep the App in the foreground"
            echo "  · Do not switch away or lock the screen during installation, or the system may kill the process and interrupt it"
            echo ""
            install_scenario common
            rc=$?
            case "$rc" in
                0)
                    echo "$PROVISION_VERSION" > "$MARKER"
                    plog "Auto install complete, wrote marker $PROVISION_VERSION"
                    echo ""
                    echo "${C_GREEN}Base dependencies installed. You are ready to go!${C_RESET}"
                    break
                    ;;
                2)
                    printf '\033[2J\033[H'
                    echo "Installation cancelled, back to the menu."
                    ;;
                *)
                    echo ""
                    echo "${C_RED}Installation failed. Ask the AI in the chat to read this terminal's output to diagnose and fix it, or re-enter the terminal and retry.${C_RESET}"
                    break
                    ;;
            esac
            ;;
        2)
            plog "User chose 2 (environment setup)"
            echo ""
            echo "${C_YELLOW}Install tips${C_RESET}:"
            echo "  · Installation takes a while; turn on background keep-alive and keep the App in the foreground"
            echo "  · Do not switch away or lock the screen during installation, or the system may kill the process and interrupt it"
            env_install_menu
            echo "$PROVISION_VERSION" > "$MARKER"
            plog "Environment setup complete, wrote marker $PROVISION_VERSION"
            echo ""
            echo "${C_GREEN}Environment setup complete. You are ready to go!${C_RESET}"
            break
            ;;
        3)
            plog "User chose 3 (manual install)"
            echo ""
            echo "${C_YELLOW}Manual install tips${C_RESET}:"
            echo "  · ripgrep (rg) is a must-have tool; missing it degrades the experience"
            echo "  · git is the foundation of the visual version-control features"
            echo "  · MCP tools depend on runtimes such as python3 / nodejs"
            echo ""
            printf "Confirm manual install and stop asking? [y/N]: "
            read manual_confirm
            if [ "$manual_confirm" = "y" ] || [ "$manual_confirm" = "Y" ]; then
                echo "$PROVISION_SKIPPED" > "$MARKER"
                plog "Manual install confirmed, wrote skip marker"
                echo "Manual install selected; you will not be prompted again when entering the terminal."
                break
            else
                printf '\033[2J\033[H'
                echo "Cancelled, back to the menu."
            fi
            ;;
        4)
            plog "User chose 4 (exit, ask again next time)"
            echo "Exited. You will be prompted again next time you enter the terminal."
            break
            ;;
        *)
            printf '\033[2J\033[H'
            echo "${C_RED}Invalid input, please select again.${C_RESET}"
            ;;
    esac
done
plog "Setup menu finished"
git_config