import 'dart:async';

import 'package:flutter/material.dart';

import '../services/privileged_installer.dart';
import '../models/privileged_provider_state.dart';

enum PrivilegedAccessStatus {
  /// Not queried about privileged access providers yet.
  notInitialized,

  /// Privileged access provider(s) is/are not installed.
  notInstalled,

  /// Privileged access provider(s) is/are not running.
  notRunning,

  /// Privileged access provider(s) is/are running but our app doesn't have permission to use it/them.
  permissionRequired,

  /// Privileged access provider(s) is/are ready to be used.
  ready,

  /// This is only applicable when both Shizuku and Porter are installed, but none of them is
  /// ready to be used. And both have different reasons for which they are not able to be used.
  notReady,

  /// This is only applicable when only Porter is installed, and there is another unofficial
  /// version of porter installed causing permission issues.
  unrecognizedManager,

  /// There is a compatibility issue with Porter (only Porter).
  compatibilityIssue,

  /// Unknown status was received from the native android side.
  unsupported,
}

class PrivilegedAccessStateProvider extends ChangeNotifier {
  PrivilegedProviderState? _stateInfo;
  Timer? _autoRefreshTimer;

  PrivilegedProviderState? get stateInfo => _stateInfo;
  PrivilegedAccessStatus get status {
    switch (_stateInfo?.status) {
      case null:
        return PrivilegedAccessStatus.notInitialized;
      case PrivilegedProviderStatuses.managerNotInstalled:
      case PrivilegedProviderStatuses.providersNotInstalled:
        return PrivilegedAccessStatus.notInstalled;
      case PrivilegedProviderStatuses.installedNotRunning:
      case PrivilegedProviderStatuses.providersNotRunning:
      case PrivilegedProviderStatuses.porterStateUnavailable:
        return PrivilegedAccessStatus.notRunning;
      case PrivilegedProviderStatuses.runningNoPermission:
      case PrivilegedProviderStatuses.providersPermissionRequired:
        return PrivilegedAccessStatus.permissionRequired;
      case PrivilegedProviderStatuses.ready:
        return PrivilegedAccessStatus.ready;
      case PrivilegedProviderStatuses.providersUnavailable:
        return PrivilegedAccessStatus.notReady;
      case PrivilegedProviderStatuses.porterIncompatible:
        return PrivilegedAccessStatus.compatibilityIssue;
      case PrivilegedProviderStatuses.porterInstalledUnrecognized:
        return PrivilegedAccessStatus.unrecognizedManager;
      default:
        return PrivilegedAccessStatus.unsupported;
    }
  }

  bool get isReady => status == PrivilegedAccessStatus.ready;
  String? get statusMessage => _stateInfo?.message;

  PrivilegedAccessStateProvider([int durationSeconds = 3]) {
    _refreshState().then((_) {
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
      (_) => _refreshState(),
    );
  }

  Future<void> _refreshState() async {
    PrivilegedProviderState newState;
    try {
      newState = await PrivilegedInstaller.getProviderState();
    } catch (_) {
      newState = const PrivilegedProviderState(
        status: 'unsupported',
        message: 'Unknown Shizuku/Porter status.',
        installed: false,
        running: false,
        permissionGranted: false,
        selectedProvider: null,
      );
    }

    if (_stateInfo != newState) {
      _stateInfo = newState;
      notifyListeners();
    }
  }
}
