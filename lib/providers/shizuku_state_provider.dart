import 'dart:async';

import 'package:flutter/material.dart';

import '../services/shizuku_installer.dart';
import '../models/shizuku_state_info.dart';

enum ShizukuStatus {
  /// Not queried about shizuku status yet
  notInitialized,

  /// Shizuku app is not installed
  notInstalled,

  /// Shizuku app is installed but not running
  notRunning,

  /// Shizuku app is installed and running, but app has no permission to use it
  permissionRequired,

  /// Some error while retrieving shizuku status
  unsupported,

  /// Shizuku is ready
  ready,
}

class ShizukuStateProvider extends ChangeNotifier {
  ShizukuStateInfo? _stateInfo;
  Timer? _autoRefreshTimer;

  ShizukuStateInfo? get stateInfo => _stateInfo;

  ShizukuStatus get status {
    switch (_stateInfo?.status) {
      case null:
        return ShizukuStatus.notInitialized;
      case 'manager_not_installed':
        return ShizukuStatus.notInstalled;
      case 'installed_not_running':
        return ShizukuStatus.notRunning;
      case 'running_no_permission':
        return ShizukuStatus.permissionRequired;
      case 'ready':
        return ShizukuStatus.ready;
      default:
        return ShizukuStatus.unsupported;
    }
  }

  bool get isReady => status == ShizukuStatus.ready;
  String? get statusMessage => _stateInfo?.message;

  ShizukuStateProvider([int durationSeconds = 3]) {
    _refreshShizukuState().then((_) {
      _startAutoRefresh(durationSeconds);
    });
  }

  @override
  void dispose() {
    _autoRefreshTimer?.cancel();
    super.dispose();
  }

  void _startAutoRefresh(int durationSeconds) {
    _autoRefreshTimer = Timer.periodic(
      Duration(seconds: durationSeconds),
      (_) => _refreshShizukuState(),
    );
  }

  Future<void> _refreshShizukuState() async {
    ShizukuStateInfo newState;
    try {
      newState = await ShizukuInstaller.getShizukuState();
    } catch (_) {
      newState = const ShizukuStateInfo(
        status: 'unsupported',
        message: 'Unable to check Shizuku state.',
        installed: false,
        running: false,
        permissionGranted: false,
      );
    }

    if (_stateInfo != newState) {
      _stateInfo = newState;
      notifyListeners();
    }
  }
}
