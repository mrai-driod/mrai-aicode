#!/bin/sh
# AiCode container environment shared library: package-manager detection, package-name mapping, version detection, mirror switching, scenario definitions and installation.
#
# Sourced by the container environment script provision.sh — its two entry points (the first-run setup menu when entering the terminal, and the environment setup menu opened by the `aicode` command)
# share this library. The logic is centralized here, so a single change takes effect at both entry points. If you change the package list / install logic
# and existing devices must re-run setup, also bump LinuxContainerEngine.PROVISION_VERSION.

# Shared library directory: used to locate android-sdk.sh and scenarios/. Passed in by provision.sh via SELF_DIR before sourcing;
# when sourced standalone it falls back to ~/.aicode/lib.
: "${AICODE_LIB_DIR:=${HOME:-/root}/.aicode/lib}"

# Multiple mirror candidates (ordered by priority; switching auto-probes and skips unavailable ones; Aliyun returns 403 for all server access, so it goes last and probing will skip it)
MIRRORS="mirrors.huaweicloud.com mirrors.tuna.tsinghua.edu.cn mirrors.ustc.edu.cn mirrors.cloud.tencent.com mirrors.aliyun.com"
MIRROR=""
# Base tools (not part of the custom checklist, always installed): git/ripgrep are the foundation of the AI workflow and version control; bash/curl are general dependencies
BASE_PKGS="bash curl ripgrep git"

# ── Diagnostic log: written to $HOME/.aicode/provision.log, visible to the host (inside the container, /root/.aicode is bound to the App's private directory).
# When the terminal PTY fails to start or hangs, the App's "Container Diagnostics" includes the tail of this file in its log to show how far the script got and
# whether the menu appeared. Beyond 256KB only the last 64KB is kept, so appending one line per terminal entry cannot grow it forever. Everything fails silently and never blocks setup.
LOG_FILE="$HOME/.aicode/provision.log"
plog() {
    [ -n "$HOME" ] || return 0
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$*" >> "$LOG_FILE" 2>/dev/null || return 0
    sz=$(wc -c < "$LOG_FILE" 2>/dev/null || echo 0)
    if [ "$sz" -gt 262144 ] 2>/dev/null; then
        tail -c 65536 "$LOG_FILE" > "$LOG_FILE.tmp" 2>/dev/null && mv "$LOG_FILE.tmp" "$LOG_FILE" 2>/dev/null
    fi
    return 0
}

# ── Terminal menu colors ──
C_BOLD=$(printf '\033[1m')
C_CYAN=$(printf '\033[36m')
C_YELLOW=$(printf '\033[33m')
C_GREEN=$(printf '\033[32m')
C_RED=$(printf '\033[31m')
C_DIM=$(printf '\033[2m')
C_RESET=$(printf '\033[0m')

# ── Package manager detection: run once before the menu, shared by installation and listing ──
detect_pmgr() {
    if command -v apk >/dev/null 2>&1; then
        PMGR=apk
    elif command -v apt-get >/dev/null 2>&1; then
        PMGR=apt
    elif command -v dnf >/dev/null 2>&1; then
        PMGR=dnf
    elif command -v yum >/dev/null 2>&1; then
        PMGR=yum
    elif command -v pacman >/dev/null 2>&1; then
        PMGR=pacman
    else
        PMGR=
    fi
}

# ── Install a batch of packages with the detected package manager ──
pkg_add() {
    case "$PMGR" in
        apk)
            apk update
            apk add --no-cache "$@"
            ;;
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -y
            apt-get install -y "$@"
            ;;
        dnf) dnf install -y "$@" ;;
        yum) yum install -y "$@" ;;
        pacman) pacman -Sy --noconfirm "$@" ;;
        *)
            echo "Unsupported package manager (apk/apt-get/dnf/yum/pacman)" >&2
            return 1
            ;;
    esac
}

# ── Detect the available openjdk package name under apt (trixie only has 21/25, no 17; bookworm defaults to 17; LTS 21 preferred)──
apt_java_headless() {
    for p in openjdk-21-jdk-headless openjdk-17-jdk-headless; do
        if apt-cache policy "$p" 2>/dev/null | grep -q '^  Candidate: [0-9]'; then
            echo "$p"
            return 0
        fi
    done
    echo "openjdk-17-jdk-headless"
}

# ── Runtime → package-name mapping for the current package manager (shared by the custom install list and installation)──
runtime_pkgs() {
    case "$PMGR:$1" in
        apk:node)    echo "nodejs npm" ;;
        apk:python)  echo "python3 py3-pip" ;;
        apk:java)    echo "openjdk17-jdk" ;;
        apk:go)      echo "go" ;;
        apt:node)    echo "nodejs npm" ;;
        apt:python)  echo "python3 python3-pip python3-venv" ;;
        apt:java)    echo "$(apt_java_headless)" ;;
        apt:go)      echo "golang-go" ;;
        dnf:node|yum:node)     echo "nodejs npm" ;;
        dnf:python|yum:python) echo "python3 python3-pip" ;;
        dnf:java|yum:java)     echo "java-17-openjdk-headless" ;;
        dnf:go|yum:go)         echo "golang" ;;
        pacman:node)   echo "nodejs npm" ;;
        pacman:python) echo "python python-pip" ;;
        pacman:java)   echo "jdk17-openjdk" ;;
        pacman:go)     echo "go" ;;
        apk:rust)      echo "rust cargo" ;;
        apk:php)       echo "php83 composer" ;;
        apt:rust)      echo "rustc cargo" ;;
        apt:php)       echo "php-cli composer" ;;
        dnf:rust|yum:rust) echo "rust cargo" ;;
        dnf:php)           echo "php-cli composer" ;;
        yum:php)           echo "php-cli" ;;
        pacman:rust)   echo "rust" ;;
        pacman:php)    echo "php composer" ;;
    esac
}

# ── List the available versions of a package in the repository (one per line, newest first; keep full version strings for pinning at install time; empty on failure)──
pkg_versions() {
    pkg="$1"
    case "$PMGR" in
        # apk search -v output looks like "name-version - description..." and -e also matches similar provides packages (e.g. nodejs-current),
        # so filter by "name-digit" to keep only real package lines and take the version from the first column; multi-source/multi-repo lines are deduped by version order (newest first)
        apk) apk search -e -v "$pkg" 2>/dev/null | grep -E "^$pkg-[0-9]" | sed -n "s/^$pkg-//p" | awk '{print $1}' | sort -Vu -r ;;
        # madison has no header, so the first line is already data; multiple sources/repos produce multiple lines, deduped by version order (newest first)
        apt) apt-cache madison "$pkg" 2>/dev/null | awk -F'|' '{gsub(/^ +| +$/, "", $2); print $2}' | sort -Vu -r ;;
        dnf) dnf list available --showduplicates "$pkg" 2>/dev/null | awk 'NR>2 {print $2}' | sort -Vu -r ;;
        yum) yum list available "$pkg" 2>/dev/null | awk 'NR>2 {print $2}' | sort -Vu -r ;;
        pacman) pacman -Si "$pkg" 2>/dev/null | sed -n 's/^Version *: //p' ;;
    esac
}

# ── Candidate main-version package detection: java/php are not pinned to a single version; list the main-version packages actually available in the repo (newest first); other runtimes use a single package ──
runtime_candidates() {
    case "$PMGR:$1" in
        # apk search outputs "name-version", which cannot be used directly; use a known candidate list + exact existence check instead, outputting bare package names
        apk:java)
            for p in openjdk25-jdk openjdk21-jdk openjdk17-jdk openjdk11-jdk openjdk8-jdk; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        apt:java)  apt-cache search '^openjdk-[0-9]*-jdk-headless$' 2>/dev/null | awk '{print $1}' | sort -V -r ;;
        dnf:java|yum:java)
            for p in java-21-openjdk-headless java-17-openjdk-headless; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        pacman:java)
            for p in jdk21-openjdk jdk17-openjdk jdk-openjdk; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        apk:php)
            for p in php84 php83 php82; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        apt:php)
            for p in php8.4-cli php8.3-cli php8.2-cli; do
                [ -n "$(pkg_versions "$p" | head -1)" ] && echo "$p"
            done
            ;;
        *)
            echo "$(runtime_pkgs "$1" | awk '{print $1}')"
            ;;
    esac
}

# ── Runtime main package version (for display; takes the default candidate and strips apk's -rN suffix)──
runtime_ver() {
    pkg_versions "$(runtime_candidates "$1" | head -1)" | head -1 | sed 's/-r[0-9]*$//'
}

# ── Update the package index (prerequisite for version detection and installation)──
pkg_update() {
    case "$PMGR" in
        apk) apk update ;;
        apt) apt-get update -y ;;
        dnf) dnf makecache -y ;;
        yum) yum makecache -y ;;
        pacman) pacman -Sy --noconfirm ;;
    esac
}

# ── Candidate main-version selection: list the available main-version packages (bare names), pick one by number (Enter/invalid input falls back to #1); the result goes into $PICKED_PKG ──
pick_candidate() {
    PICKED_PKG=""
    cands=$(runtime_candidates "$1")
    [ -z "$cands" ] && return 1
    n=1
    for c in $cands; do
        echo "    $n) $c"
        n=$((n + 1))
    done
    printf "Select main version (number): "
    read ans
    case "$ans" in
        ""|*[!0-9]*) PICKED_PKG=$(echo "$cands" | head -1) ;;
        *) PICKED_PKG=$(echo "$cands" | sed -n "${ans}p" | head -1) ;;
    esac
    [ -z "$PICKED_PKG" ] && PICKED_PKG=$(echo "$cands" | head -1)
}

# ── Runtime main package name (custom install prefers the chosen candidate; falls back to the default candidate)──
pkg_for() {
    case "$1" in
        node)   [ -n "$node_pkg" ] && echo "$node_pkg" || runtime_candidates node | head -1 ;;
        python) [ -n "$python_pkg" ] && echo "$python_pkg" || runtime_candidates python | head -1 ;;
        java)   [ -n "$java_pkg" ] && echo "$java_pkg" || runtime_candidates java | head -1 ;;
        go)     [ -n "$go_pkg" ] && echo "$go_pkg" || runtime_candidates go | head -1 ;;
        rust)   [ -n "$rust_pkg" ] && echo "$rust_pkg" || runtime_candidates rust | head -1 ;;
        php)    [ -n "$php_pkg" ] && echo "$php_pkg" || runtime_candidates php | head -1 ;;
    esac
}

# ── Selected runtime main package version (for display)──
ver_for() {
    pkg_versions "$(pkg_for "$1")" | head -1 | sed 's/-r[0-9]*$//'
}

# ── Runtime install package list (chosen candidate main package + companion packages)──
runtime_install() {
    rest=$(runtime_pkgs "$1" | awk '{$1=""; sub(/^ /, ""); print}')
    if [ -n "$rest" ]; then
        echo "$(pkg_for "$1") $rest"
    else
        pkg_for "$1"
    fi
}

# ── One install-plan line: name → package list (with a version when one was detected; display strips apk's -rN suffix)──
plan_line() {
    line="    · $1 → $2"
    if [ -n "$3" ]; then
        disp=$(echo "$3" | sed 's/-r[0-9]*$//')
        line="$line(v$disp)"
    fi
    echo "$line"
}

# ── y/N prompt: $1 is the prompt text, $2 the default (y/n), $3 a version (optional); answering y returns 0, n returns 1 ──
ask_yn() {
    def="$2"
    while :; do
        if [ "$def" = "y" ]; then
            printf "%s (install by default) [Y/n]: " "$1"
        else
            printf "%s (skip by default) [y/N]: " "$1"
        fi
        read ans
        [ -z "$ans" ] && ans="$def"
        case "$ans" in
            y|Y) return 0 ;;
            n|N) return 1 ;;
            *) echo "  Please enter y or n" ;;
        esac
    done
}

# ── Show the install plan and confirm: $1 is a space-separated list of runtimes (may be empty); entering y returns 0, anything else returns 1 ──
show_plan() {
    echo ""
    echo "══════════════ Install Plan ══════════════"
    echo "  Base tools: $BASE_PKGS"
    runtimes="$1"
    if [ -n "$runtimes" ]; then
        echo "  Runtimes:"
        for r in $runtimes; do
            case "$r" in
                node)   plan_line "Node.js" "$(runtime_install node)" "$(ver_for node)" ;;
                python) plan_line "Python 3" "$(runtime_install python)" "$(ver_for python)" ;;
                java)   plan_line "Java" "$(runtime_install java)" "$(ver_for java)" ;;
                go)     plan_line "Go" "$(runtime_install go)" "$(ver_for go)" ;;
                rust)   plan_line "Rust" "$(runtime_install rust)" "$(ver_for rust)" ;;
                php)    plan_line "PHP" "$(runtime_install php)" "$(ver_for php)" ;;
            esac
        done
    else
        echo "  Runtimes: (none selected, base tools only)"
    fi
    echo "══════════════════════════════════════"
    printf "  Enter y to start installing, any other key returns to the menu: "
    read ans
    [ "$ans" = "y" ] || [ "$ans" = "Y" ]
}

# ── Mirror switching interaction: list the mirror candidates for single selection (1 = auto-detect, middle entries = specific mirrors, last = no switch), then perform the switch once chosen ──
ask_mirror() {
    echo ""
    echo "Select a mirror source:"
    echo "    1) Auto-detect (recommended)"
    n=2
    for m in $MIRRORS; do
        echo "    $n) $m"
        n=$((n + 1))
    done
    echo "    0) Do not switch (keep current)"
    printf "Enter a number (Enter defaults to 1): "
    read ans
    case "$ans" in
        ""|*[!0-9]*|1)
            pick_mirror
            ;;
        0)
            echo "Keeping the default source; no switch."
            return 0
            ;;
        *)
            MIRROR=$(echo "$MIRRORS" | awk -v i="$((ans - 1))" '{print $i}')
            [ -z "$MIRROR" ] && { echo "Invalid number; using auto-detect."; pick_mirror; }
            ;;
    esac
    setup_mirror
}

# ── Custom install: pick runtimes one by one; base tools are always installed; runs after the plan is confirmed ──
# Returns: 0 = done, 1 = install failed, 2 = user cancelled (plan not confirmed)
install_custom() {
    echo ""
    echo "Custom install will install the base tools ($BASE_PKGS) together with the selected runtimes."
    echo ""
    ask_mirror
    echo "Updating the package list..."
    pkg_update || { echo "${C_RED}Failed to update the package list. Check the network and try again.${C_RESET}"; return 1; }
    echo ""
    echo "Select the dependencies to install"
    runtimes=""
    node_pkg=""; python_pkg=""; java_pkg=""; go_pkg=""; rust_pkg=""; php_pkg=""
    if ask_yn "Install Node.js?" y; then
        echo "  Available Node.js main versions:"
        pick_candidate node
        node_pkg=$PICKED_PKG
        runtimes="$runtimes node"
    fi
    if ask_yn "Install Python 3?" y; then
        echo "  Available Python 3 main versions:"
        pick_candidate python
        python_pkg=$PICKED_PKG
        runtimes="$runtimes python"
    fi
    if ask_yn "Install Java?" n; then
        echo "  Available Java main versions:"
        pick_candidate java
        java_pkg=$PICKED_PKG
        runtimes="$runtimes java"
    fi
    if ask_yn "Install Go?" n; then
        echo "  Available Go main versions:"
        pick_candidate go
        go_pkg=$PICKED_PKG
        runtimes="$runtimes go"
    fi
    if ask_yn "Install Rust?" n; then
        echo "  Available Rust main versions:"
        pick_candidate rust
        rust_pkg=$PICKED_PKG
        runtimes="$runtimes rust"
    fi
    if ask_yn "Install PHP?" n; then
        echo "  Available PHP main versions:"
        pick_candidate php
        php_pkg=$PICKED_PKG
        runtimes="$runtimes php"
    fi
    if [ -z "$runtimes" ]; then
        echo "No runtime selected; only the base tools ($BASE_PKGS) will be installed."
    fi
    show_plan "$runtimes" || return 2
    echo ""
    echo "Installing the selected dependencies (this may take a few minutes, please wait)..."
    pkgs="$BASE_PKGS"
    for r in $runtimes; do
        pkgs="$pkgs $(runtime_install "$r")"
    done
    pkg_add $pkgs || return 1
    return 0
}

# ── Probe the HTTP status code of a single URL (curl preferred, wget as fallback, 000 when it cannot be probed)──
http_code() {
    if command -v curl >/dev/null 2>&1; then
        curl -s -o /dev/null --max-time 8 -w '%{http_code}' "$1" 2>/dev/null || echo "000"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -T 8 -O /dev/null "$1" 2>/dev/null && echo "200" || echo "000"
    else
        echo "000"
    fi
}

# ── Probe whether mirror $1 is usable from this container: pick a real content path per package manager;
#    apk requires a direct HTTP 2xx (3xx redirects to https, unusable because minirootfs has no CA certificates);
#    apt accepts 2xx/3xx (apt can follow https redirects); dnf/pacman count as usable on any response ──
probe_mirror() {
    m="$1"
    case "$PMGR" in
        apk)
            code=$(http_code "http://$m/alpine/v3.21/main/x86_64/APKINDEX.tar.gz")
            case "$code" in 2[0-9][0-9]) return 0 ;; *) return 1 ;; esac
            ;;
        apt)
            . /etc/os-release 2>/dev/null
            if [ "$ID" = "ubuntu" ]; then
                url="http://$m/ubuntu/dists/jammy/Release"
            else
                url="http://$m/debian/dists/stable/Release"
            fi
            code=$(http_code "$url")
            case "$code" in 2[0-9][0-9]|3[0-9][0-9]) return 0 ;; *) return 1 ;; esac
            ;;
        dnf|yum|pacman)
            code=$(http_code "https://$m/archlinux/core/os/x86_64/core.db")
            case "$code" in 2[0-9][0-9]|3[0-9][0-9]|4[0-9][0-9]) return 0 ;; *) return 1 ;; esac
            ;;
    esac
}

# ── Pick a currently usable mirror from the candidates (falls back to the first one if none is usable)──
pick_mirror() {
    for m in $MIRRORS; do
        if probe_mirror "$m"; then
            MIRROR="$m"
            return 0
        fi
    done
    MIRROR=$(echo "$MIRRORS" | awk '{print $1}')
}

# ── Mirror source: switch to a China mirror per package manager (multiple candidates, auto-probed); on failure the original config is restored automatically (does not block later installation)──
setup_mirror() {
    [ -z "$MIRROR" ] && pick_mirror
    if command -v apk >/dev/null 2>&1; then
        setup_apk_mirror
    elif command -v apt-get >/dev/null 2>&1; then
        setup_apt_mirror
    elif command -v dnf >/dev/null 2>&1; then
        setup_dnf_mirror
    elif command -v yum >/dev/null 2>&1; then
        echo "yum (RHEL/CentOS) does not support automatic mirror switching yet; using the default source" >&2
        return 0
    elif command -v pacman >/dev/null 2>&1; then
        setup_pacman_mirror
    fi
}

setup_apk_mirror() {
    # Use http rather than https: minirootfs has no ca-certificates, and apk verifies the signatures of the index and packages independently, so http does not affect integrity.
    mkdir -p /etc/apk
    # The Alpine major-version branch is read dynamically from the mirror itself, to support user-imported Alpine images of different versions:
    # 1) Prefer extracting it from the existing repositories (both the official source and previously switched sources contain `alpine/<branch>/`, and edge works too);
    # 2) If that fails, fall back to VERSION_ID in /etc/os-release (e.g. 3.21.3 → v3.21);
    # 3) Last resort: v3.21 (matches the bundled Alpine).
    branch=""
    if [ -f /etc/apk/repositories ]; then
        branch=$(sed -n 's#.*alpine/\([^/]*\)/.*#\1#p' /etc/apk/repositories 2>/dev/null | head -1)
    fi
    if [ -z "$branch" ] && [ -f /etc/os-release ]; then
        . /etc/os-release 2>/dev/null
        if [ "$ID" = "alpine" ] && [ -n "$VERSION_ID" ]; then
            branch="v$(echo "$VERSION_ID" | cut -d. -f1-2)"
        fi
    fi
    [ -z "$branch" ] && branch="v3.21"
    cat > /etc/apk/repositories <<EOF
http://$MIRROR/alpine/$branch/main
http://$MIRROR/alpine/$branch/community
EOF
}

setup_apt_mirror() {
    . /etc/os-release 2>/dev/null
    codename="${VERSION_CODENAME:-}"
    [ -z "$codename" ] && { echo "Cannot identify the apt release codename; skipping mirror switch" >&2; return 1; }
    # On ARM (common on phones), Ubuntu packages live in the ubuntu-ports repository (ports.ubuntu.com); x86 uses the main ubuntu repository.
    # The main Debian repository already includes arm64/armhf, so no ports repo is needed.
    arch=$(dpkg --print-architecture 2>/dev/null || uname -m)
    case "$ID" in
        ubuntu)
            case "$arch" in
                amd64|i386) repo="ubuntu" ;;
                *) repo="ubuntu-ports" ;;
            esac
            uri="http://$MIRROR/$repo/"
            suites="$codename $codename-updates $codename-backports $codename-security"
            components="main restricted universe multiverse"
            ;;
        debian)
            uri="http://$MIRROR/debian/"
            suites="$codename $codename-updates $codename-backports"
            components="main contrib non-free non-free-firmware"
            security_uri="http://$MIRROR/debian-security/"
            ;;
        *) echo "Unsupported apt distribution: $ID; skipping mirror switch" >&2; return 1 ;;
    esac
    # Write Signed-By only when the keyring exists; without a keyring apt falls back to trusted.gpg.d
    signed_by=""
    [ -f /usr/share/keyrings/ubuntu-archive-keyring.gpg ] && signed_by="Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg"
    [ -f /usr/share/keyrings/debian-archive-keyring.gpg ] && signed_by="Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg"
    # Back up and clear the old sources (handle both the sources.list and sources.list.d formats)
    backup_dir=/etc/apt/mirror-backup
    rm -rf "$backup_dir" && mkdir -p "$backup_dir"
    [ -f /etc/apt/sources.list ] && cp /etc/apt/sources.list "$backup_dir/" 2>/dev/null
    [ -d /etc/apt/sources.list.d ] && cp -a /etc/apt/sources.list.d "$backup_dir/" 2>/dev/null
    rm -f /etc/apt/sources.list
    rm -rf /etc/apt/sources.list.d
    mkdir -p /etc/apt/sources.list.d
    {
        echo "Types: deb"
        echo "URIs: $uri"
        echo "Suites: $suites"
        echo "Components: $components"
        [ -n "$signed_by" ] && echo "$signed_by"
    } > /etc/apt/sources.list.d/aicode-mirror.sources
    if [ -n "$security_uri" ]; then
        {
            echo "Types: deb"
            echo "URIs: $security_uri"
            echo "Suites: $codename-security"
            echo "Components: $components"
            [ -n "$signed_by" ] && echo "$signed_by"
        } > /etc/apt/sources.list.d/aicode-mirror-security.sources
    fi
    # Verify: if update fails, print the reason and restore the original config
    if ! apt-get update -y; then
        echo "apt update failed after switching mirrors; restored the original source configuration" >&2
        rm -rf /etc/apt/sources.list.d
        [ -f "$backup_dir/sources.list" ] && cp "$backup_dir/sources.list" /etc/apt/sources.list
        [ -d "$backup_dir/sources.list.d" ] && mv "$backup_dir/sources.list.d" /etc/apt/sources.list.d
        rm -rf "$backup_dir"
        return 1
    fi
    rm -rf "$backup_dir"
    return 0
}

setup_dnf_mirror() {
    # Fedora: back up the original repo files and write a Huawei Cloud baseurl (the China metalink is unusable, so overwrite the repo files directly)
    backup_dir=/etc/yum.repos.d/mirror-backup
    rm -rf "$backup_dir" && mkdir -p "$backup_dir"
    cp -a /etc/yum.repos.d/ "$backup_dir/" 2>/dev/null
    rm -f /etc/yum.repos.d/*.repo
    cat > /etc/yum.repos.d/fedora.repo <<EOF
[fedora]
name=Fedora \$releasever - \$basearch
baseurl=https://$MIRROR/fedora/releases/\$releasever/Everything/\$basearch/os/
enabled=1
gpgcheck=1
EOF
    cat > /etc/yum.repos.d/fedora-updates.repo <<EOF
[updates]
name=Fedora \$releasever - \$basearch - Updates
baseurl=https://$MIRROR/fedora/updates/\$releasever/\$basearch/
enabled=1
gpgcheck=1
EOF
    if ! dnf makecache -y >/dev/null 2>&1; then
        echo "dnf makecache failed after switching mirrors; restored the original repo configuration" >&2
        rm -f /etc/yum.repos.d/fedora.repo /etc/yum.repos.d/fedora-updates.repo
        cp -a "$backup_dir/." /etc/yum.repos.d/ 2>/dev/null
        rm -rf "$backup_dir"
        return 1
    fi
    rm -rf "$backup_dir"
    return 0
}

setup_pacman_mirror() {
    backup=/etc/pacman.d/mirrorlist
    [ -f "$backup" ] && cp "$backup" "$backup.backup"
    cat > "$backup" <<EOF
Server = https://$MIRROR/archlinux/\$repo/os/\$arch
EOF
    if ! pacman -Sy --noconfirm >/dev/null 2>&1; then
        echo "pacman -Sy failed after switching mirrors; restored the original mirrorlist" >&2
        [ -f "$backup.backup" ] && mv "$backup.backup" "$backup"
        return 1
    fi
    rm -f "$backup.backup"
    return 0
}

git_config() {
    # Skip credential setup when git is not installed (manual-install / exit path)
    command -v git >/dev/null 2>&1 || return 0
    # Minimal credential injection: only applies to repositories under the workspace root ($HOME/workspace/) —
    # credential.helper is written into gitconfig.credential and loaded conditionally by directory via includeIf,
    # so git repositories elsewhere in the container are not injected (the custom aicode helper decodes the credentials file: on a hit it passes instantly; on a miss it asks via file IPC for a dialog to fill them in).
    # Use $HOME instead of hardcoding /root: the container home is decided by the environment; keep it consistent (on the App side, GIT_CONFIG_GLOBAL likewise points to $HOME/.aicode/.gitconfig).
    AICODE_DIR="$HOME/.aicode"
    mkdir -p "$AICODE_DIR"
    cat > "$AICODE_DIR/gitconfig.credential" <<EOF
[credential]
    helper = $AICODE_DIR/git-credential-aicode
EOF
    # First clear the old includeIf section (idempotent), then write the includeIf scoped to the workspace root.
    # The section name must include the subsection: the real section is [includeIf "gitdir:…"], so writing only `--remove-section includeIf`
    # does not match it; the resulting fatal error is swallowed by 2>/dev/null, so every setup run --adds another duplicate path.
    git config --global --remove-section "includeIf.gitdir:$HOME/workspace/" 2>/dev/null || true
    git config --global --add includeIf."gitdir:$HOME/workspace/".path "$AICODE_DIR/gitconfig.credential"
    # Remove the global top-level [credential] section: the helper must only be loaded per-directory via the includeIf above;
    # putting it at the top level would apply it to every repository in the container, breaking the minimal "inject only under the workspace root" design.
    git config --global --remove-section credential 2>/dev/null || true
    # The default initial branch for new repositories is uniformly main (the standard on modern platforms such as GitHub / GitLab; avoids master-related mismatches)
    git config --global init.defaultBranch main
}

# ── Scenario framework ──
# One script per development environment, placed in lib/scenarios/, defining scenario_name_<id> and install_scenario_<id>;
# SCENARIO_IDS decides the menu order. android / flutter are heavier; their logic lives in their own files (sharing lib/android-sdk.sh).
SCENARIO_IDS="common python node android flutter java go rust php base"

# Load the shared helpers and all scenario scripts (android-sdk.sh is shared by the android / flutter scenarios)
[ -f "$AICODE_LIB_DIR/android-sdk.sh" ] && . "$AICODE_LIB_DIR/android-sdk.sh"
for f in "$AICODE_LIB_DIR/scenarios/"*.sh; do
    [ -f "$f" ] && . "$f"
done

# Scenario name (falls back to the id when undefined)
scenario_name() {
    fn="scenario_name_$1"
    if command -v "$fn" >/dev/null 2>&1; then "$fn"; else echo "$1"; fi
}

# Scenario install dispatch
install_scenario() {
    fn="install_scenario_$1"
    if command -v "$fn" >/dev/null 2>&1; then "$fn"; else echo "${C_RED}Unknown scenario: $1${C_RESET}"; return 1; fi
}

# ── Light scenario installer (called by lightweight scenarios): switch mirror → update index → show the plan for confirmation → install packages.
#    Uses each runtime's default latest main version; to pin a main version, use "Custom install".
#    Returns: 0 = done, 1 = install failed, 2 = user cancelled (plan not confirmed)
install_light_runtimes() {
    runtimes="$1"
    ask_mirror
    echo ""
    echo "Updating the package list..."
    pkg_update || { echo "${C_RED}Failed to update the package list. Check the network and try again.${C_RESET}"; return 1; }
    echo ""
    show_plan "$runtimes" || return 2
    echo ""
    echo "Starting installation (this may take a few minutes, please wait)..."
    pkgs="$BASE_PKGS"
    for r in $runtimes; do
        pkgs="$pkgs $(runtime_install "$r")"
    done
    pkg_add $pkgs || return 1
    return 0
}
