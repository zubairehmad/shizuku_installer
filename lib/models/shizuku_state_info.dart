class ShizukuStateInfo {
  const ShizukuStateInfo({
    required this.status,
    required this.message,
    required this.installed,
    required this.running,
    required this.permissionGranted,
  });

  /// It can have one of the following values:
  ///
  /// `"manager_not_installed"` : Shizuku app is not installed.
  ///
  /// `"installed_not_running"` : Shizuku app is installed but the service is not running.
  ///
  /// `"running_no_permission"` : The app is installed and running but our app has no permissions to use it.
  ///
  /// `"ready"` : The shizuku service is ready for installation process
  ///
  /// `"unsupported"` : There is some error while retrieving the shizuku status
  final String status;
  final String message;
  final bool installed;
  final bool running;
  final bool permissionGranted;

  factory ShizukuStateInfo.fromMap(Map<Object?, Object?> map) {
    return ShizukuStateInfo(
      status: (map['status'] as String?) ?? 'unsupported',
      message: (map['message'] as String?) ?? 'Unknown Shizuku state.',
      installed: (map['installed'] as bool?) ?? false,
      running: (map['running'] as bool?) ?? false,
      permissionGranted: (map['permissionGranted'] as bool?) ?? false,
    );
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other is ShizukuStateInfo &&
            status == other.status &&
            installed == other.installed &&
            running == other.running &&
            permissionGranted == other.permissionGranted);
  }

  @override
  int get hashCode =>
      Object.hash(status, installed, running, permissionGranted);
}
