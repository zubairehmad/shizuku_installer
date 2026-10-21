class PrivilegedProviderStatuses {
  PrivilegedProviderStatuses._();

  /// The shizuku or porter app is not installed
  static const managerNotInstalled = 'manager_not_installed';

  /// The shizuku or porter app is installed but service is not running.
  static const installedNotRunning = 'installed_not_running';

  /// The shizuku or porter service is running but we have no permission to use it.
  static const runningNoPermission = 'running_no_permission';

  /// The shizuku or porter service is ready to be used.
  static const ready = 'ready';

  /// Porter is running but its protocol is not supported.
  static const porterIncompatible = 'porter_incompatible';

  /// An unofficial/modified/fork version of Porter is present and has given permissions to application.
  static const porterInstalledUnrecognized = 'porter_installed_unrecognized';

  /// Porter reported a connection, but it is no longer avaiable.
  static const porterStateUnavailable = 'porter_state_unavailable';

  /// Both shizuku and porter are not installed.
  static const providersNotInstalled = 'providers_not_installed';

  /// Both shizuku and porter are not running.
  static const providersNotRunning = 'providers_not_running';

  /// Both shizuku and porter haven't granted permission to the app.
  static const providersPermissionRequired = 'providers_permission_required';

  /// Both shizuku and porter are installed, but not ready to be used.
  /// One may have permission issues, while other is not running.
  static const providersUnavailable = 'providers_unavailable';
}

/// Represents status of privileged api provider app (Shizuku or Porter)
class PrivilegedProviderState {
  /// It will be one of the [PrivilegedProviderStatuses].
  final String status;
  final String message;
  final String? selectedProvider;
  final bool installed;
  final bool running;
  final bool permissionGranted;

  const PrivilegedProviderState({
    required this.status,
    required this.message,
    required this.selectedProvider,
    required this.installed,
    required this.running,
    required this.permissionGranted,
  });

  factory PrivilegedProviderState.fromMap(Map<Object?, Object?> map) {
    return PrivilegedProviderState(
      status: (map['status'] as String?) ?? 'unsupported',
      message: (map['message'] as String?) ?? 'Unknown Shizuku/Porter status.',
      selectedProvider: map['selectedProvider'] as String?,
      installed: (map['installed'] as bool?) ?? false,
      running: (map['running'] as bool?) ?? false,
      permissionGranted: (map['permissionGranted'] as bool?) ?? false,
    );
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other is PrivilegedProviderState &&
            status == other.status &&
            installed == other.installed &&
            message == other.message &&
            selectedProvider == other.selectedProvider &&
            running == other.running &&
            permissionGranted == other.permissionGranted);
  }

  @override
  int get hashCode => Object.hash(
    status,
    installed,
    message,
    selectedProvider,
    running,
    permissionGranted,
  );
}
