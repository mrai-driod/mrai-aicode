# Building Flutter apps in the container

Once the JDK, Android SDK, and Flutter SDK are set up in AiCode's Linux container (PRoot), you can build the Android APK of a Flutter app directly from source. This article uses Debian 12 (bookworm) aarch64 as the example and has been tested end-to-end inside a PRoot container, successfully building a debug APK.

::: tip Scope of support
An ARM64 container only supports building **debug APKs** (JIT mode, with native ARM64 support). **release / profile packages cannot be built locally**: Google only publishes gen_snapshot for Android AOT on linux-x64, so AOT compilation can't run on an ARM64 host. If you need a release package, use a remote build such as GitHub Actions.
:::

::: tip Mirrors and networking
This article uses domestic mirrors for installation and building by default (faster and more reliable downloads). Mirror artifacts are fully equivalent to the official ones: even if your network can reach official sources such as `dl.google.com` directly, there's no need to switch back to the official configuration.
:::

## Environment baseline

| Item | Value |
| --- | --- |
| System | Debian GNU/Linux 12 (bookworm) aarch64 (PRoot) |
| JDK | OpenJDK 17 (headless) |
| Flutter | 3.27.4 stable (git clone at a specific tag) |
| Dart | 3.6.2 (linux-arm64) |
| Gradle | Provided by the project wrapper (8.3); no system install needed |
| SDK | platforms android-35, build-tools 33.0.1 + 35.0.0, platform-tools |
| aapt2 | ARM64 statically built version (lzhiyong/android-sdk-tools) |

## 1. Install the basic dependencies

```bash
apt update
apt install -y openjdk-17-jdk-headless curl unzip zip git
```

Verify the JDK:

```bash
java -version
# openjdk version "17..."
```

## 2. Install the Android SDK

By default this uses a domestic mirror for installation (faster and more reliable downloads). Except for sdkmanager itself (cmdline-tools), which has to be downloaded and unpacked manually, all components are installed with a single command by overriding sdkmanager's default repository root URL with the `SDK_TEST_BASE_URL` environment variable (it must end with `/`, and the mirror must provide `repository2-3.xml`); both `--list` and `--install` have been tested and work:

```bash
# The only manual step: get sdkmanager itself (download from the mirror)
mkdir -p ~/android/sdk && cd ~/android/sdk
curl -fL -C - -o clt.zip https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip
unzip -q clt.zip
mkdir -p cmdline-tools/latest && mv cmdline-tools/* cmdline-tools/latest/

# Install all components from the mirror (directory layout and license acceptance handled automatically)
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=$HOME/android/sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
export SDK_TEST_BASE_URL=https://mirrors.cloud.tencent.com/AndroidSDK/

yes | sdkmanager --install "platforms;android-35" "build-tools;33.0.1" "build-tools;35.0.0" "platform-tools"
```

::: tip Choosing versions
The Flutter 3.27 template has `compileSdk` 35 (needs `platforms;android-35`), and the default `buildToolsVersion` is **33.0.1** (if it's missing you'll get `Failed to find Build Tools revision 33.0.1`). build-tools 35.0.0 is used for the aapt2 override in section 5 — optional but recommended.
:::

::: tip Mirrors vs. the official source
Although `SDK_TEST_BASE_URL` is an environment variable meant for testing (not a documented regular configuration), it is widely used by mirror setups. An SDK installed from a mirror is fully equivalent to an official installation: even if your network can reach `dl.google.com` directly, there's no need to switch back to the official flow. If this approach fails in a specific scenario, you can switch to the official source (below) or assemble the SDK manually.
:::

### Optional: install from the official source

If your network can reach `dl.google.com` directly, you can also use the official standard flow:

```bash
mkdir -p ~/android/sdk && cd ~/android/sdk
curl -O https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q commandlinetools-linux-*.zip
mkdir -p cmdline-tools/latest && mv cmdline-tools/* cmdline-tools/latest/

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=$HOME/android/sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

yes | sdkmanager --install "platforms;android-35" "build-tools;33.0.1" "build-tools;35.0.0" "platform-tools"
```

### Alternative: assemble the SDK manually from the mirror

If the `SDK_TEST_BASE_URL` approach doesn't work, you can download the component zips from the Tencent Cloud mirror and assemble them manually. The result is `build-tools/`, `platforms/`, `platform-tools/`, and `cmdline-tools/` directories, with the same effect as sdkmanager:

| Component | Mirror URL |
| --- | --- |
| commandlinetools | `https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip` |
| platform-35 | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-35_r02.zip` |
| build-tools 33.0.1 | `https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r33.0.1-linux.zip` |
| build-tools 35.0.0 | `https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip` |
| platform-tools | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r35.0.2-linux.zip` |

When downloading large files from a mirror, connections are prone to dropping; use `curl -fL -C -` for resumable downloads together with a retry loop. Assemble the directories:

```bash
cd ~/android/sdk
# cmdline-tools: unpacks to cmdline-tools/, rename it to latest
unzip clt.zip && mv cmdline-tools cmdline-tools/latest   # The actual unpacked layout depends on the zip
# platform-35: unpacks to android-35/, move it into platforms/
mkdir -p platforms && mv android-35 platforms/android-35
# build-tools: the zip unpacks to android-13/ (33.0.1) or android-15/ (35.0.0); move them into the matching version directories
mkdir -p build-tools/33.0.1 build-tools/35.0.0
mv android-13/* build-tools/33.0.1/ && mv android-15/* build-tools/35.0.0/
# platform-tools: unpacks directly to platform-tools/, no moving needed
```

## 3. Replace the native binaries with ARM64 builds

Google's official tools such as `aapt2`, `aidl`, `zipalign`, and `adb` are built for the x86_64 architecture and can't run on aarch64 (an architecture limitation, unrelated to networking), so they must be replaced with the community-provided ARM64 static builds. Download from `https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip`; if the download is slow or fails, prepend a GitHub proxy prefix to the original link: `https://gh-proxy.com/https://github.com/...`

```bash
curl -L -O https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
unzip android-sdk-tools-static-aarch64.zip -d ~/armtools35
# Overwrite build-tools (both the 33.0.1 and 35.0.0 directories) and platform-tools
cp -p ~/armtools35/build-tools/* ~/android/sdk/build-tools/35.0.0/
cp -p ~/armtools35/build-tools/* ~/android/sdk/build-tools/33.0.1/
cp -p ~/armtools35/platform-tools/* ~/android/sdk/platform-tools/
chmod +x ~/android/sdk/build-tools/*/* ~/android/sdk/platform-tools/*
```

Verify:

```bash
~/android/sdk/build-tools/35.0.0/aapt2 version   # Normal if it prints a version number
~/android/sdk/build-tools/35.0.0/zipalign        # Normal if it prints usage output
```

## 4. Install the Flutter SDK

Clone the specified tag with git:

```bash
git clone --depth 1 --branch 3.27.4 https://github.com/flutter/flutter.git ~/.local/flutter-3.27.4
export PATH=$PATH:~/.local/flutter-3.27.4/bin

flutter --version   # The first run automatically downloads the dart-sdk and engine artifacts
```

::: tip Handling a slow dart-sdk install under PRoot
The first run of `flutter --version` automatically downloads the dart-sdk (about 210MB) and unpacks it. Under PRoot this step may sit unresponsive for a long time (slow download, slow unpacking, lock waits). If it gets stuck, complete the installation manually to bypass the automatic bootstrap:

```bash
cd ~/.local/flutter-3.27.4/bin/cache
EV=$(cat ../internal/engine.version)     # e.g. 82bd5b7209295a5b7ff8cae0df96e7870171e3a5
curl -fL -o dart-sdk-linux-arm64.zip \
  "https://storage.flutter-io.cn/flutter_infra_release/flutter/$EV/dart-sdk-linux-arm64.zip"
# When the network allows it, you can use the official source instead: https://storage.googleapis.com/flutter_infra_release/flutter/$EV/dart-sdk-linux-arm64.zip
unzip -q dart-sdk-linux-arm64.zip -d .   # Unpacks to dart-sdk/
rm -f dart-sdk-linux-arm64.zip
echo "$EV" > engine-dart-sdk.stamp       # Write the stamp and flutter will skip the automatic download
```

Verify:

```bash
~/.local/flutter-3.27.4/bin/cache/dart-sdk/bin/dart --version
# Dart SDK version: 3.6.2 ... on "linux_arm64"
```
:::

## 5. Configure

### local.properties

`flutter create` generates this automatically (including `sdk.dir` and `flutter.sdk`), so there's no need to write it by hand — just confirm the contents are correct:

```bash
cat ~/workspace/<project>/android/local.properties
# sdk.dir=/root/android/sdk
# flutter.sdk=/root/.local/flutter-3.27.4
```

### Global Gradle configuration

The aapt2 override should be written to the global file, not into the project's `gradle.properties`:

```bash
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=/root/android/sdk/build-tools/35.0.0/aapt2" > ~/.gradle/gradle.properties
```

The container has about 4G of memory available, and the project `android/gradle.properties` default of `-Xmx4G` can trigger an OOM, so lowering it is recommended:

```properties
org.gradle.jvmargs=-Xmx2G -XX:MaxMetaspaceSize=1G -XX:+HeapDumpOnOutOfMemoryError
```

### Mirror configuration (default)

When Gradle resolves dependencies, if the repository list contains `google()` (pointing at dl.google.com) and the network can't reach it, the build will hang for a long time on connection timeouts. The recommended default is to use mirror repositories directly (builds are faster), and even if the network can reach the official sources there's no need to revert the following configuration:

1. **Gradle wrapper mirror**: the `distributionUrl` in the project's `android/gradle/wrapper/gradle-wrapper.properties` points at `services.gradle.org` by default; change it to a domestic mirror:

```
distributionUrl=https\://mirrors.huaweicloud.com/gradle/gradle-8.3-all.zip
```

2. **Dependency repository mirrors**: remove `google()` / `mavenCentral()` / `gradlePluginPortal()` from all repositories and keep only the Aliyun mirrors:

```groovy
maven { url 'https://maven.aliyun.com/repository/google' }
maven { url 'https://maven.aliyun.com/repository/central' }
maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }   // Add when the plugin repository is needed
```

There are 5 places that need changing in total:

| File | Description |
| --- | --- |
| Project `android/settings.gradle` | pluginManagement.repositories |
| Project `android/build.gradle` | allprojects.repositories |
| `flutter SDK/packages/flutter_tools/gradle/settings.gradle.kts` | The :gradle subproject repositories (without this change, AGP 7.3.0 will connect to dl.google.com) |
| `flutter SDK/packages/flutter_tools/gradle/resolve_dependencies.gradle` | Dependency pre-resolution |
| `flutter SDK/packages/flutter_tools/gradle/src/main/kotlin/dependency_version_checker.gradle.kts` | Version-check buildscript |

::: warning Note
The Flutter engine artifact repository (`download.flutter.io`) is injected automatically by the Flutter plugin, and its URL is built from `FLUTTER_STORAGE_BASE_URL` — do not delete it. Of the 5 places above, only the google/mavenCentral entries need handling. Files inside the SDK are overwritten when Flutter is upgraded, so the changes must be reapplied.
:::

3. **Engine artifacts and dependency download sources**: set environment variables at build time so Flutter's dart-sdk, engine artifacts, and pub packages go through the Chinese mirror or the official source:

```bash
export FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn   # Engine artifacts etc. via the Chinese mirror
unset PUB_HOSTED_URL    # Use the official pub.dev (generally reachable directly); pub.flutter-io.cn has had cases of content-hash verification failures
```

## 6. Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH=$PATH:~/.local/flutter-3.27.4/bin
export FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn   # Engine artifacts etc. via the Chinese mirror (recommended default)
unset PUB_HOSTED_URL    # Use the official pub.dev (recommended default, reachable directly)

cd ~/workspace/<project>
flutter build apk --debug --target-platform android-arm64
```

The environment variables above are the recommended default configuration (explained in section 5). If the network can reach the official sources, you can keep them as-is too — no need to revert.

The first build takes about 3–10 minutes (downloading the Gradle distribution and dependencies and compiling, depending on the network); incremental builds afterwards take about 1–2 minutes. The Gradle daemon works normally under PRoot; if the daemon exits abnormally and causes lock file conflicts, add `--no-daemon` or delete `~/.gradle/caches/*.lock`.

On success, the APK is output to:

```
build/app/outputs/flutter-apk/app-debug.apk
```

You can verify it with `aapt2 dump badging`:

```bash
~/android/sdk/build-tools/35.0.0/aapt2 dump badging build/app/outputs/flutter-apk/app-debug.apk | head -3
```

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Connect to dl.google.com timed out` (hangs for a long time) | The repositories contain `google()`/`mavenCentral()` and dl.google.com is unreachable | Use mirror repositories as in section 5 (the default configuration) |
| `Could not resolve io.flutter:flutter_embedding_debug` | Same as above: engine dependency resolution reaches for dl.google.com | Same as above |
| `Failed to find Build Tools revision 33.0.1` | The Flutter template defaults to buildTools 33.0.1, and the SDK lacks that version | Install `build-tools;33.0.1` (and replace the ARM64 binaries) |
| `aapt2 ... cannot execute binary / syntax error` | Using the x86 build of aapt2 | Make sure `aapt2FromMavenOverride` in `~/.gradle/gradle.properties` points to the ARM64 build |
| Flutter bootstrap download of the dart-sdk unresponsive for a long time / repeated lock waits | Automatic download and unpacking are unstable under PRoot | Install the dart-sdk manually as in section 4 and write the stamp |
| `Downloaded archive for xxx had wrong content-hash` (pub retries repeatedly) | The pub mirror returned a corrupted package | `unset PUB_HOSTED_URL` to use the official pub.dev, and clear `~/.pub-cache` |
| `flutter doctor` Android toolchain check times out | The doctor verifies dl.google.com online via sdkmanager internally | This is normal when dl.google.com is unreachable and doesn't affect building |
| Gradle lock file conflict | The last build didn't exit cleanly | Delete `~/.gradle/caches/*.lock` or add `--no-daemon` |
| No build progress visible when the command is piped | `\| tail` buffers the output | Run the build command directly, don't pipe to tail; failure details are visible in the first 1–3 minutes during dependency resolution |

## Limitations and tips

- **release/profile can't be built on ARM64**: gen_snapshot for Android AOT only exists in a linux-x64 build. If you need a release package, use GitHub Actions (run `flutter build apk --release` on x64) or another remote build method.
- The `flutter create` template uses `compileSdk=35` and `buildToolsVersion=33.0.1`; before modifying the template, confirm the corresponding SDK components are installed.
- Under PRoot, unpacking large numbers of small files (such as the dart-sdk and dependency caches) is slow — that's normal, don't mistake it for a hang.
- If dependency resolution keeps failing, first verify the reachability of the repository URL with `curl -sI`, then check the repository order.
