# Building Android apps in the container

Once the JDK and Android SDK are set up in AiCode's Linux container, you can build Android apps directly from source. This article uses Debian 12 (bookworm) aarch64 as the example; Ubuntu, Arch, and other distributions work the same way.

::: tip Prefer a custom Debian / Ubuntu image
The built-in Alpine container uses musl libc, and building Android apps under PRoot needs extra handling there (see the supplement at the end). See "Custom container images" for how to import a custom image.
:::

## Environment baseline

| Item | Value |
| --- | --- |
| System | Debian GNU/Linux 12 (bookworm) aarch64 |
| JDK | OpenJDK 17 |
| Gradle | Provided by the project wrapper; no system install needed |
| SDK | Android 36 / build-tools 35.0.0 |
| aapt2 | ARM64 statically built version 35.0.2 (community build) |

## 1. Install the basic dependencies

```bash
apt update
apt install -y openjdk-17-jdk-headless curl wget unzip ripgrep
```

Verify the JDK:

```bash
/usr/lib/jvm/java-17-openjdk-arm64/bin/java -version
# Should output openjdk version "17..."
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

yes | sdkmanager --install "platforms;android-36" "build-tools;35.0.0" "platform-tools"
```

::: tip Choosing versions
Pick the component versions according to the target project's `compileSdk` and `buildToolsVersion`; the examples in this article use Android 36 / build-tools 35.0.0.
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

yes | sdkmanager --install "platforms;android-36" "build-tools;35.0.0" "platform-tools"
```

### Alternative: assemble the SDK manually from the mirror

If the `SDK_TEST_BASE_URL` approach doesn't work, you can download the component zips from the Tencent Cloud mirror and assemble them manually. The result is `build-tools/`, `platforms/`, and `platform-tools/` directories, with the same effect as sdkmanager:

| Component | Mirror URL |
| --- | --- |
| commandlinetools | `https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip` |
| platform-36 | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-36_r01.zip` |
| build-tools 35.0.0 | `https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip` |
| platform-tools | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r35.0.2-linux.zip` |

```bash
cd /workspace
curl -L -O https://mirrors.cloud.tencent.com/AndroidSDK/platform-36_r01.zip
curl -L -O https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip
curl -L -O https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r35.0.2-linux.zip

# 1) build-tools: the zip unpacks to a directory called android-15/; move its contents into build-tools/35.0.0/
unzip build-tools_r35_linux.zip -d ~/android/sdk
mkdir -p ~/android/sdk/build-tools/35.0.0
mv ~/android/sdk/android-15/* ~/android/sdk/build-tools/35.0.0/

# 2) platform: unpacks to android-36/
unzip platform-36_r01.zip -d ~/android/sdk
mkdir -p ~/android/sdk/platforms
mv ~/android/sdk/android-36 ~/android/sdk/platforms/android-36

# 3) platform-tools: unpacks directly to platform-tools/, no moving needed
unzip platform-tools_r35.0.2-linux.zip -d ~/android/sdk

ls ~/android/sdk   # You should see the three directories build-tools/ platforms/ platform-tools/
```

## 3. Replace the native binaries with ARM64 builds

Google's official tools such as `aapt2` and `adb` are built for x86_64 and can't run on aarch64, so they need to be replaced with community-maintained ARM64 static builds.

Download from `https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip`. If the download is slow or fails, you can prepend a GitHub proxy prefix to the original link (tested working, 2026-08):

| Acceleration service | Usage |
| --- | --- |
| gh-proxy.com | `https://gh-proxy.com/https://github.com/...` |
| ghproxy.vip | `https://ghproxy.vip/https://github.com/...` |

```bash
# Example: download via the gh-proxy.com accelerator
curl -L -O https://gh-proxy.com/https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
# If the container can reach GitHub directly, download it directly
# curl -L -O https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
unzip /workspace/android-sdk-tools-static-aarch64.zip -d ~/armtools35
```

Replace the binaries in the SDK:

```bash
cp -p ~/armtools35/build-tools/* ~/android/sdk/build-tools/35.0.0/
cp -p ~/armtools35/platform-tools/* ~/android/sdk/platform-tools/
```

Verify that `aapt2` runs:

```bash
~/android/sdk/build-tools/35.0.0/aapt2 version
# Should output the version number with no error
```

## 4. Configure

### local.properties (project root)

```bash
echo "sdk.dir=/root/android/sdk" > /workspace/local.properties
```

This file is usually already covered by `.gitignore`, so it won't pollute the repository.

### Global Gradle configuration

Write the aapt2 override into the global file, not into the project's `gradle.properties`:

```bash
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=/root/android/sdk/build-tools/35.0.0/aapt2" > ~/.gradle/gradle.properties
```

### apt sources (optional)

The container may not be able to reach the official sources by default, so switching to a domestic mirror is recommended:

```
URIs: http://mirrors.ustc.edu.cn/debian
URIs: http://mirrors.ustc.edu.cn/debian-security
```

### Downloading a Gradle distribution (optional)

When a project uses the Gradle wrapper there's no need for a system Gradle, but when setting up a brand-new project from scratch you first need a distribution. If the official source `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip` downloads slowly, use a mirror:

- Huawei Cloud: `https://mirrors.huaweicloud.com/gradle/gradle-8.11.1-bin.zip`
- Tencent Cloud: `https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`

```bash
curl -L -o /workspace/gradle.zip https://mirrors.huaweicloud.com/gradle/gradle-8.11.1-bin.zip
unzip gradle.zip -d /workspace/tools
/workspace/tools/gradle-8.11.1/bin/gradle --version   # Use it to run builds or generate the wrapper
```

## 5. Build

```bash
cd /workspace
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 \
ANDROID_HOME=/root/android/sdk \
./gradlew assembleDebug
```

If dependencies download slowly, you can switch to the Aliyun mirror in the `dependencyResolutionManagement.repositories` block of `settings.gradle.kts` (by default AGP pulls dependencies from `google()` / `mavenCentral()`):

```kotlin
maven { url = uri("https://maven.aliyun.com/repository/google") }
maven { url = uri("https://maven.aliyun.com/repository/central") }
maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
```

The first build takes about 10 to 15 minutes (downloading dependencies plus Kotlin compilation); incremental builds afterwards take 1 to 2 minutes. The Gradle daemon works normally under PRoot and speeds up incremental builds; if the daemon exits abnormally and causes lock file conflicts, add `--no-daemon` for that run.

On success, the APK output path depends on the project configuration, usually:

```
app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk
```

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `JAVA_HOME is not set` | Environment variable not set | Add `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64` on the command line |
| `SDK location not found` | Missing local.properties | Create `local.properties` with `sdk.dir=/root/android/sdk` |
| `aapt2 syntax error` | Using the x86 build of aapt2 | Make sure `aapt2FromMavenOverride` in `~/.gradle/gradle.properties` points to the ARM build |
| `Failed to install build-tools` | AGP's online verification failed | Make sure the build-tools version the project needs is installed locally |
| apt download returns 403 | Mirror temporarily unavailable | Switch mirror (Tsinghua → USTC) |
| Gradle lock file conflict | The last build didn't exit cleanly | Delete `~/.gradle/caches/*.lock`, or add `--no-daemon` |

## Supplement: fixing the built-in Alpine container

When building an Android app in the built-in Alpine container, you may hit `Failed to delete /tmp/tempdir_*`. The cause is that `TemporaryFile` in AGP's apkzlib uses `File.delete()` to delete temporary directories, and that operation can fail in a PRoot environment. Custom Debian images are not affected.

::: warning Version-specific
The code below targets apkzlib in Gradle 8.11.1 / AGP 8.x (package name `com.android.tools.build.apkzlib.bytestorage.TemporaryFile`). The package path and internal structure may differ between versions; adjust it according to the decompiled structure of the jar you actually use.
:::

Replace `File.delete()` with `Files.delete()` (which goes through unlinkat, bypassing the broken low-level delete):

```java
package com.android.tools.build.apkzlib.bytestorage;

import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;

public class TemporaryFile implements Closeable {
    private boolean deleted;
    private final File file;

    public TemporaryFile(File file) {
        this.deleted = false;
        this.file = file;
    }

    public File getFile() {
        Preconditions.checkState(!this.deleted, "File already deleted");
        return this.file;
    }

    @Override
    public void close() throws IOException {
        if (this.deleted) {
            return;
        }
        this.deleted = true;
        deleteFile(this.file);
    }

    private void deleteFile(File f) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    deleteFile(kid);
                }
            }
        }
        if (f.exists()) {
            try {
                Files.delete(f.toPath());
            } catch (NoSuchFileException e) {
                // Already deleted; ignore
            } catch (IOException e) {
                if (!f.delete()) {
                    throw new IOException("Failed to delete '" + f.getAbsolutePath() + "'", e);
                }
            }
        }
    }
}
```

Compile this source and replace the `.class` file back into the `instrumented-apkzlib-*.jar` in the Gradle cache, and you're done.
