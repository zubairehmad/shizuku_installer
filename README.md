# shizuku_installer

Shizuku Installer is a package installer designed for android. It avoids using default system package installer and directly uses core system pm (package manager) to install the android package with the help of shizuku service.

### Use cases:
In the following cases this app may prove to be useful:
- The default package installer UI is corrupted, blocked, or unreliable.
- OEM installer screens add extra steps or restrictions; this UI stays consistent.

### Prerequisite:
- Shizuku installed
- Rooted android device (optional)

### How to use:
- Give app the permission to use shizuku service in Shizuku app.
- Run the shizuku service:
  - `Rooted Devices` :  Use the start via root option in the shizuku.
  - `Android 11+ (API 30+)` : Run the service through wireless debugging. Follow the in-app instructions.
  - `Older android version` :  It will require a computer everytime you need to run the service. Its better to just use adb directly (as the app itself uses adb for installing) instead of installing this app.
- Once everything is setup correctly, the app UI will show "Shizuku is ready".
- Select the installable file and install it.

### Supported Installable Files:
- **.apk**
- **.xapk**

### Download APK:
- Download the apk for the app from [here](https://github.com/zubairehmad/shizuku_installer/releases/tag/v1.0.0).
