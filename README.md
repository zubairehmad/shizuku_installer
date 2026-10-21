# Shizuku Installer

Shizuku Installer is an Android package installer that uses privileged access through **Shizuku or Porter** to install Android packages directly through the system package manager (`pm`), without relying on the default system installer UI.

## Use Cases

This app may be useful when:
- The default package installer UI is corrupted, blocked, or unreliable.
- OEM installer screens add extra steps or restrictions, and you prefer a consistent installation UI.

## Prerequisites

- **Shizuku or Porter** installed and configured.
- A rooted Android device is optional.

## How to Use

### Option 1: Shizuku

1. Install and open Shizuku.
2. Start the Shizuku service:
   - **Rooted devices:** Use the *Start via root* option.
   - **Android 11+ (API 30+):** Start the service through Wireless debugging. Follow Shizuku's in-app instructions.
   - **Android versions below 11:** You will need a computer and ADB to start the service. Whenever the service stops, you must use the computer to start it again. If you frequently need to restart the service, using ADB directly may be more convenient.
3. Open Shizuku Installer and grant it permission to use Shizuku through the Shizuku app.
4. Wait until the app indicates that privileged access is ready.
5. Select an installable file and tap **Install**.

### Option 2: Porter

1. Install and configure Porter on your device.
2. Start the Porter service using Porter's supported setup method.
3. Open Shizuku Installer and follow any permission prompts required by Porter.
4. Wait until the app indicates that privileged access is ready.
5. Select an installable file and tap **Install**.

**Note:** On Android versions below 11, if you use a service setup that requires ADB, you will need a computer to start the service yourself whenever it stops. Root-based startup may avoid this requirement if supported by your setup.

Shizuku Installer automatically selects an available supported provider. If both Porter and Shizuku are ready, Porter is preferred.

## Supported Installable Files

- `.apk`
- `.xapk`

## Download APK

Download the latest release from [GitHub Releases](https://github.com/zubairehmad/shizuku_installer/releases).
