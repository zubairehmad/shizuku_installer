import 'package:flutter/services.dart';

import '../models/privileged_provider_state.dart';

class PrivilegedInstaller {
  PrivilegedInstaller._();

  static const MethodChannel _channel = MethodChannel(
    'adb_pkg_installer/privileged',
  );

  static void setOnOpenFileListener({
    required Future<void> Function(String) listener,
  }) {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onOpenFile':
          await listener(call.arguments as String);
          break;
        default:
          throw MissingPluginException();
      }
    });
  }

  static Future<String?> getPendingUri() async {
    return await _channel.invokeMethod<String?>('getPendingUri');
  }

  static Future<String?> copyUriToTempDir(String uriString) async {
    return await _channel.invokeMethod<String?>('copyUriToTempDir', {
      'uriString': uriString,
    });
  }

  static Future<PrivilegedProviderState> getProviderState() async {
    final result = await _channel.invokeMapMethod(
      'getProviderState',
    );
    return PrivilegedProviderState.fromMap(result ?? {});
  }

  static Future<bool> installApk(String apkPath) async {
    return await _channel.invokeMethod<bool>(
          'installApk',
          {'apkPath': apkPath},
        ) ??
        false;
  }

  static Future<bool> installXapk(String xapkPath) async {
    return await _channel.invokeMethod<bool>(
          'installXapk',
          {'xapkPath': xapkPath},
        ) ??
        false;
  }

  static Future<bool> cancelInstall() async {
    return await _channel.invokeMethod<bool>('cancelInstall') ?? false;
  }
}
