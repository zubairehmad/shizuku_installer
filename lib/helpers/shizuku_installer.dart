import 'package:flutter/services.dart';

import '../models/shizuku_state_info.dart';

class ShizukuInstaller {
  ShizukuInstaller._();

  static const MethodChannel _channel = MethodChannel(
    'adb_pkg_installer/shizuku',
  );

  static Future<ShizukuStateInfo> getShizukuState() async {
    final Map<Object?, Object?>? result = await _channel
        .invokeMapMethod<Object?, Object?>('getShizukuState');
    return ShizukuStateInfo.fromMap(result ?? <Object?, Object?>{});
  }

  static Future<bool> installApk(String apkPath) async {
    final bool? result = await _channel.invokeMethod<bool>(
      'installApk',
      <String, Object>{'apkPath': apkPath},
    );
    return result ?? false;
  }

  static Future<bool> installXapk(String xapkPath) async {
    final bool? result = await _channel.invokeMethod<bool>(
      'installXapk',
      <String, Object>{'xapkPath': xapkPath},
    );
    return result ?? false;
  }

  static Future<bool> cancelInstall() async {
    final bool? result = await _channel.invokeMethod<bool>('cancelInstall');
    return result ?? false;
  }
}
