# Installing Playwright Browser Automation

After installing Chromium in the container and registering the Playwright MCP server, the AI can open web pages, click elements, fill in forms, scrape page content, and take screenshots.

::: tip A Debian / Ubuntu container is required
The Chrome for Testing binaries depend on glibc, while the built-in Alpine image uses musl libc and can't run them. First import a custom Debian or Ubuntu image by referring to [Container & Images](/guide/container).
:::

## Environment Baseline

| Item | Value |
| --- | --- |
| System | Debian / Ubuntu, root by default inside the container |
| Architecture | aarch64 (arm64) or x86_64 |
| Browser | Chrome for Testing 153.0.8010.12, installed to `/opt/chrome` |
| MCP service | `@playwright/mcp`, fetched on demand by `npx` |

## 1. Install Dependencies

```bash
apt-get update -qq && apt-get install -y -qq unzip fonts-noto-cjk
```

Without Chinese fonts, Chinese in screenshots will display as blocks.

## 2. Download and Extract Chromium

Taking arm64 as the example; for x86_64, replace `linux-arm64` in the URL with `linux64` and the extraction directory name with `chrome-linux64`.

```bash
mkdir -p /opt/chrome
curl -L -o /tmp/chrome.zip \
  https://cdn.npmmirror.com/binaries/chrome-for-testing/153.0.8010.12/linux-arm64/chrome-linux-arm64.zip
unzip -q /tmp/chrome.zip -d /opt/chrome
rm /tmp/chrome.zip

/opt/chrome/chrome-linux-arm64/chrome --version
# Outputs Google Chrome for Testing 153.0.8010.12
```

The above uses the npmmirror mirror; you can also use the official source; when that version is taken down, just replace the version number in the URL:

```
https://storage.googleapis.com/chrome-for-testing-public/153.0.8010.12/linux-arm64/chrome-linux-arm64.zip
```

## 3. Register the MCP Server

Go to "Settings → MCP Servers → +", choose local stdio as the transport type, fill in per the table below and save; the app connects automatically.

| Field | Value |
| --- | --- |
| Name | `playwright` |
| Launch command | `npx` |
| Command arguments | `-y`, `@playwright/mcp@latest`, `--headless`, `--no-sandbox`, `--executable-path`, `/opt/chrome/chrome-linux-arm64/chrome` (add item by item) |
| Scope | Global (shared by all projects) or project (current workspace only) |

You can also edit the configuration file directly — global is `~/.aicode/mcp.json`, project-level is `<workspace>/.aicode/mcp.json` — and it takes effect automatically within seconds after saving:

```json
{
    "mcpServers": {
        "playwright": {
            "command": "npx",
            "args": [
                "-y",
                "@playwright/mcp@latest",
                "--headless",
                "--no-sandbox",
                "--executable-path",
                "/opt/chrome/chrome-linux-arm64/chrome"
            ],
            "enabled": true
        }
    }
}
```

Here `--no-sandbox` must be specified inside the container, otherwise the browser crashes on launch; `--executable-path` points to the installed Chromium, avoiding Playwright downloading again.

The default headless User-Agent carries a `HeadlessChrome` marker, and some sites block based on it; you can append `--user-agent` to override (added as two consecutive argument items in the UI):

```
--user-agent
Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36
```

Output files such as screenshots are written by default to `.playwright-mcp/` under the run directory, i.e. the current workspace root, which leaves untracked files in the repository. Appending `--output-dir` moves them out of the workspace:

```
--output-dir
/root/playwright-output
```

The exception is when a file name is specified for a screenshot: relative paths resolve against the workspace and bypass `--output-dir`, so write an absolute path.

## 4. Verify

Go to "Settings → MCP Servers"; the `playwright` row should show as connected with the tool count labeled; if the connection fails, tap the log button in the top right to troubleshoot.

Then create a new session (the tool list is determined when a session starts), have the AI open a web page and screenshot it; getting an image back means the chain works. The first connection requires waiting for `npx` to download `@playwright/mcp`, measured at 3 to 4 minutes.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Target closed` immediately on launch, or crashes out | Missing `--no-sandbox` | Add the parameter and reconnect |
| Running chrome directly in the terminal reports `/dev/shm` FATAL | The container has no `/dev/shm`, and `/dev` isn't writable | Add `--disable-dev-shm-usage`; launches by Playwright are unaffected |
| `chrome: not found`, or the binary can't execute | The container is Alpine (musl), or the package doesn't match the architecture | Switch to a Debian / Ubuntu image; verify arm64 vs x64 packages |
| Chinese in screenshots displays as blocks | Missing Chinese fonts | Run `apt-get install -y fonts-noto-cjk` |
| Screenshot is a blank page or fully transparent | Rendering occasionally fails in a no-GPU environment | Retry once; if still blank, use JPEG format |
| First call requires waiting several minutes | `npx` is downloading `@playwright/mcp` | Wait for completion; afterwards the local cache is used; you can also switch the npm registry to `https://registry.npmmirror.com` |
| No `playwright` in the MCP list | Configuration didn't take effect, or the JSON has a syntax error | Confirm `mcp.json` parses correctly, then tap "Reconnect" in the top right |

## Appendix: One-Click Install Script

The following script completes steps 1 to 3, additionally including automatic download-source switching, archive verification, skipping the download when already installed, and backing up `mcp.json` before writing. Save it as `/tmp/install-playwright.sh` and run `bash /tmp/install-playwright.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

VERSION="${CHROME_VERSION:-153.0.8010.12}"
DIR=/opt/chrome
ZIP=/tmp/chrome-$VERSION.zip

case "$(uname -m)" in
  aarch64|arm64) SUB=linux-arm64; NAME=chrome-linux-arm64 ;;
  x86_64)        SUB=linux64;     NAME=chrome-linux64 ;;
  *) echo "Unsupported architecture: $(uname -m)"; exit 1 ;;
esac
BIN="$DIR/$NAME/chrome"

[ "$(id -u)" = 0 ] || { echo "Please run as root"; exit 1; }
command -v python3 >/dev/null || { echo "python3 is missing"; exit 1; }
command -v npx >/dev/null || echo "[Warning] npx not found; the MCP service cannot start"

need=()
command -v unzip >/dev/null || need+=(unzip)
fc-list :lang=zh 2>/dev/null | grep -q . || need+=(fonts-noto-cjk)
if [ ${#need[@]} -gt 0 ]; then
  echo "Installing dependencies: ${need[*]}"
  apt-get update -qq && apt-get install -y -qq "${need[@]}"
fi

if [ -x "$BIN" ]; then
  echo "Already installed, skipping download: $BIN"
else
  for url in \
    "https://cdn.npmmirror.com/binaries/chrome-for-testing/$VERSION/$SUB/$NAME.zip" \
    "https://storage.googleapis.com/chrome-for-testing-public/$VERSION/$SUB/$NAME.zip"
  do
    echo "Downloading: $url"
    curl -fL --retry 3 --retry-delay 2 -o "$ZIP" "$url" && break
    echo "[Warning] This source is unavailable; switching to the next one"
  done
  [ -s "$ZIP" ] || { echo "All download sources failed"; exit 1; }

  mkdir -p "$DIR"
  unzip -tq "$ZIP" && unzip -q "$ZIP" -d "$DIR"
  rm -f "$ZIP"
fi

"$BIN" --version

BIN="$BIN" python3 - <<'PY'
import json, os, shutil
p = os.path.expanduser("~/.aicode/mcp.json")
os.makedirs(os.path.dirname(p), exist_ok=True)
data = {}
if os.path.exists(p):
    shutil.copy(p, p + ".bak")
    with open(p, encoding="utf-8") as f:
        data = json.load(f)   # Errors out without overwriting if there's a syntax error
data.setdefault("mcpServers", {})["playwright"] = {
    "command": "npx",
    "args": ["-y", "@playwright/mcp@latest", "--headless", "--no-sandbox",
             "--executable-path", os.environ["BIN"]],
    "enabled": True,
}
with open(p, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=4, ensure_ascii=False)
print("playwright configuration written")
PY

echo "Installation complete: $BIN"
```

`CHROME_VERSION` can specify another version. After installation completes, return to step 4 to verify.
