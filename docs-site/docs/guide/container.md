# Containers & Images

The environment in which the AI executes commands in AiCode is called a "container"; the entry point is "Settings → Containers & Images". The list is single-select: the selected container is the environment where AI command execution, file reading/writing, and the terminal live.

There are two kinds of backends, and one of them must be selected: a **local container** (running a Linux system on the phone) and **remote SSH** (running on a remote server — see [Remote SSH Mode](/guide/remote-ssh)).

## Container List

Each row is a container configuration, with a source mark after the name: built-in, custom, or SSH.

Three operations apply to all kinds of containers:

- **Edit**: the pencil icon at the end of the row.
- **Reset**: the refresh icon at the top right of the edit dialog. This is a destructive operation — it deletes the tools and configuration installed inside the container and needs a second confirmation; after confirming, the running AI sessions and all terminal tabs are closed. Deleting a large container with lots of content may take tens of seconds; during that time, "Cleaning container data" and the count of cleaned items are shown, and it closes automatically when done, without blocking the UI.
- **Delete**: swipe a row left to reveal the red delete button. Deleting a local image also cleans up its rootfs to free space (also showing cleaning progress); deleting a remote SSH entry only deletes the configuration, without affecting server data.

The built-in Alpine can also be deleted. After deleting everything, the list shows an empty state where you can restore the built-in Alpine with one tap, so there's no need to worry about accidental deletion.

After a container runs, the app automatically identifies its system type and shows the corresponding icon on the left of the list (Alpine, Ubuntu, Debian, CentOS supported); if it can't be identified, a default icon is shown.

## Built-in Alpine Container

The app ships with an Alpine image, automatically added to the list and selected on first launch, with the shell path already configured — no extra setup needed.

::: tip First use requires entering the terminal to complete initialization
The local container's environment preparation (extracting the rootfs and installing basic tools) is triggered the first time you enter the **terminal page**. For first use, enter the terminal first to complete initialization; after that, AI command execution, Git management, and local MCP can work normally.
:::

## The Initialization Menu on First Entering the Terminal

For both the built-in Alpine and self-imported images, after the rootfs extraction completes, the first time you enter the terminal an initialization menu pops up, with four options:

**1. Auto-install dependencies (recommended)**

Lists the items to install (basic tools such as bash, curl, ripgrep, git, as well as Node.js and Python 3); enter `y` to confirm, then choose a software source and start installing.

**2. Environment installation**

Opens the "Environment Installation" menu, where you pick a development environment by scenario (see the next section). A scenario is a combination of runtimes; once selected, confirm the list to install. The menu also offers "Custom Installation", where you check items one by one and specify the major version.

**3. Manual installation (don't prompt again)**

Skips installation; afterwards, entering the terminal no longer pops up this menu, and you install tools yourself. To restore the menu, reset the container.

**4. Exit**

Don't install this time; entering the terminal next time will prompt again.

An installation failure doesn't affect entering the shell; re-enter the terminal to try again.

### Software Source Selection

When switching sources, you can choose:

- `1) Auto-detect (recommended)`: measures the reachability of each mirror source under the current package manager, automatically skipping invalid or blocked sources.
- `2~6)` Specified mirror sources: Huawei Cloud, Tsinghua tuna, USTC, Tencent Cloud, Alibaba Cloud.
- `0)` Don't switch sources; keep the default.

Before switching sources, the original configuration is backed up, and failures restore automatically, without affecting subsequent installation. The built-in Alpine's rootfs keeps the official source; switch to a domestic source in this menu when needed.

## Installing Development Dependencies Anytime (aicode)

The initialization menu only pops up once, the first time you enter the terminal. Afterwards, if you need to install other runtimes (e.g. Java or Go), run the command `aicode` in the terminal to open the "Environment Installation" menu.

`aicode` installs by scenario; select a scenario and confirm the list:

| Scenario | Installs |
| --- | --- |
| General development | Basic tools + Node.js + Python 3 |
| Python development | Basic tools + Python 3 + pip |
| Node.js / Frontend development | Basic tools + Node.js + npm |
| Kotlin / Android development | Basic tools + JDK 17 + Android SDK |
| Flutter development | Basic tools + JDK + Android SDK + Flutter SDK |
| Java development | Basic tools + JDK |
| Go development | Basic tools + Go |
| Rust development | Basic tools + rustc + cargo |
| PHP development | Basic tools + PHP + Composer |
| Basic tools only | bash, curl, ripgrep, git |

The basic tools (bash, curl, ripgrep, git) are installed in every scenario. By default a scenario installs the latest major version from the software repository; to specify a version, use "Custom Installation" in the menu to check items one by one. The menu also supports switching the software source separately and viewing installed runtimes.

"Kotlin / Android development" and "Flutter development" are heavy scenarios: they download the JDK, Android SDK (including build-tools and platform-tools), and Flutter SDK from domestic mirrors, taking a long time and using a lot of storage. It's recommended to install them in a Debian / Ubuntu image (the built-in Alpine needs extra handling because it uses musl libc); on ARM64 devices, native binaries such as aapt2 that can execute are substituted automatically. Note that an ARM64 container can only build Flutter debug APKs; release packages need remote building. After installation completes, a newly opened terminal automatically has environment variables such as `JAVA_HOME` and `ANDROID_HOME`; for specific build steps see "Building an Android App in the Container" and "Building a Flutter App in the Container" in the advanced tutorials.

The settings icon at the top right of the terminal opens the terminal settings panel, where you can adjust the color theme, font size, font, and cursor style.

## Custom Images

The built-in Alpine satisfies most scenarios. If you need Debian / Ubuntu, or an environment with a specific toolchain preinstalled, you can add an image yourself in two ways.

### Method 1: Built-in Image Download (Recommended)

1. Open "Settings → Containers & Images" and tap the download icon at the top right.
2. The page lists downloadable images (Alpine, Ubuntu, Debian, etc.); tap a row to view details and download.
3. At the top right you can switch the download source (official source / Huawei Cloud / Alibaba Cloud / Tencent Cloud); for domestic networks, a domestic source is recommended.
4. After the download completes, tap "Import"; the image is added to the container list and can be used once selected.

Downloaded images can be imported repeatedly; swipe left to delete a downloaded image file.

### Method 2: Manually Import an Image File

When you need a specific version or distribution, you can prepare a rootfs image yourself and import it.

**Image requirements**:

- Compression format: `.tar.gz`, `.tgz`, `.tar.xz`, `.txz`
- Content must be a complete Linux rootfs (file system root directory, containing `/bin/sh` or another shell)
- Architecture must match the device: aarch64 images for ARM64 devices, x86_64 images for x86 devices

**Image sources**:

- Alpine (small size, fast startup): `https://alpine.linuxhub.cn/alpine/edge/releases/`, in the aarch64 directory choose `alpine-minirootfs-*-aarch64.tar.gz`
- Ubuntu: `https://cdimage.ubuntu.com/ubuntu-base/releases/`, choose `ubuntu-base-*-base-arm64.tar.gz`
- Debian: can be exported from the official Docker Hub image (e.g. `arm64v8/debian`)

With a Docker environment available, you can export a rootfs from any image:

```bash
docker pull debian:bookworm
docker create --name temp-rootfs debian:bookworm
docker export temp-rootfs | gzip > debian-rootfs.tar.gz
docker rm temp-rootfs
```

You can also make one yourself in an existing Linux environment with `debootstrap` (Debian / Ubuntu) or `alpine-make-rootfs` (Alpine).

**Import steps**:

1. Open "Settings → Containers & Images", tap + at the top right, and choose "Local Image".
2. Fill in the configuration:
   - **Name**: an alias for the image configuration.
   - **Shell path**: the shell inside the container, e.g. `/bin/sh` or `/bin/bash`.
   - **Image file**: choose the prepared rootfs file. On save, a copy is copied to the app's private directory; from then on, resetting the container re-extracts from that copy, independent of the original file; deleting the image cleans up the copy too.
   - **Mounts** (optional): mount phone directories into the container — see "Mounting Phone Directories for the AI" below.
   - **proot arguments** (optional): added item by item, appended as-is to the PRoot startup arguments.
   - **Environment variables** (optional): key-value pairs injected into processes inside the container.
3. After saving, select the configuration in the list to switch to the custom container.

The first time you use a custom image, the app extracts the rootfs to the private directory, taking time depending on the image size. Custom images don't have a domestic software source configured by default; you can switch in the initialization menu on first entering the terminal.

### Common Troubleshooting

**Package installation errors**: caused by hard links being unavailable inside the container. Edit image → proot arguments → add `--link2symlink` → save, then re-enter the terminal. Images imported with the built-in download feature already carry this argument by default.

**Arrow keys shown as `^[[A`**: the current shell is `sh`, which doesn't support line editing. Edit the container and change the shell to `/bin/bash` to get arrow keys, history, and completion.

## Mounting Phone Directories for the AI

The AI runs inside the container and by default can only see the workspace, unable to access the phone's public storage. To grant access, mount a phone directory into the container:

1. Confirm storage permission is granted. A permission request pops up on first use; if you denied it before, go to System Settings → Apps → AiCode → Permissions → Storage and enable it.
2. Open "Settings → Containers & Images" and edit the container currently in use.
3. In "Mounts", add an entry: **Local directory** — the path on the phone (e.g. `/sdcard/Download`); **Container directory** — the path inside the container (e.g. `/mnt/Download`).
4. After saving, restart the terminal session, and the AI can access it via the in-container path.

::: warning Mount only what's needed
Only mount the subdirectories you need; don't mount the entire `/sdcard` directly — that exposes all files on the phone to the AI, a privacy leak risk.
:::

Example: to let the AI view app logs, mount the local directory `/storage/emulated/0/Android/data/com.aicode/` to the container's `/mnt/aicode`, and you can read the logs under `/mnt/aicode/files/logs/`. Note that under `Android/data/` only AiCode's own directory can be accessed; other apps' directories can't be accessed due to scoped storage restrictions.

If only a single file needs handling, a simpler way is to copy it into the workspace, which the AI can access by default.

## Behavior After Deleting Everything

Even if you delete all containers (including the built-in Alpine), the app still works normally — when the AI executes commands, it falls back to the built-in Alpine as the default execution environment. Reopen the "Containers & Images" page, and on the empty-state page you can restore the built-in Alpine with one tap or add a new image.
