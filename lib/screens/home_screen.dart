import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

import '../helpers/shizuku_installer.dart';
import '../models/shizuku_state_info.dart';
import '../widgets/file_selection_section.dart';
import '../widgets/shizuku_status_section.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({
    super.key,
    required this.themeMode,
    required this.onThemeModeChanged,
  });

  final ThemeMode themeMode;
  final ValueChanged<ThemeMode> onThemeModeChanged;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  String? _selectedPath;
  bool _isInstalling = false;
  bool _isCheckingShizuku = true;
  ShizukuStateInfo? _shizukuState;
  Timer? _shizukuRefreshTimer;

  bool get _isShizukuReady => _shizukuState?.status == 'ready';

  static const Set<String> _installableExtensions = <String>{'apk', 'xapk'};

  bool get _hasValidSelection {
    final String? selectedPath = _selectedPath;
    if (selectedPath == null) {
      return false;
    }

    final String lowerName = selectedPath.toLowerCase().split('/').last;
    final int dotIndex = lowerName.lastIndexOf('.');
    if (dotIndex <= 0 || dotIndex == lowerName.length - 1) {
      return false;
    }

    return _installableExtensions.contains(lowerName.substring(dotIndex + 1));
  }

  @override
  void initState() {
    super.initState();
    _refreshShizukuState();
    _startShizukuAutoRefresh();
  }

  @override
  void dispose() {
    _shizukuRefreshTimer?.cancel();
    super.dispose();
  }

  void _startShizukuAutoRefresh() {
    _shizukuRefreshTimer?.cancel();
    _shizukuRefreshTimer = Timer.periodic(
      const Duration(seconds: 5),
      (_) => _refreshShizukuState(showSpinner: false),
    );
  }

  Future<void> _refreshShizukuState({bool showSpinner = true}) async {
    if (showSpinner) {
      setState(() {
        _isCheckingShizuku = true;
      });

      await Future.delayed(const Duration(seconds: 1));
    }

    ShizukuStateInfo nextState;
    try {
      nextState = await ShizukuInstaller.getShizukuState();
    } catch (_) {
      nextState = const ShizukuStateInfo(
        status: 'unsupported',
        message: 'Unable to check Shizuku state.',
        installed: false,
        running: false,
        permissionGranted: false,
      );
    }

    if (!mounted) {
      return;
    }

    if (showSpinner) {
      setState(() {
        _shizukuState = nextState;
        _isCheckingShizuku = false;
      });
      return;
    }

    if (_isSameShizukuState(_shizukuState, nextState)) {
      return;
    }

    setState(() {
      _shizukuState = nextState;
    });
  }

  bool _isSameShizukuState(ShizukuStateInfo? a, ShizukuStateInfo? b) {
    if (a == null || b == null) {
      return a == b;
    }
    return a.status == b.status &&
        a.message == b.message &&
        a.installed == b.installed &&
        a.running == b.running &&
        a.permissionGranted == b.permissionGranted;
  }

  Future<void> _openFileSelector() async {
    final bool permissionGranted = await _ensureStoragePermission();
    if (!permissionGranted) {
      return;
    }

    final FilePickerResult? result = await FilePicker.platform.pickFiles(
      allowMultiple: false,
      type: FileType.any,
      withData: false,
    );

    if (result == null || result.files.isEmpty) {
      return;
    }

    final String? selectedPath = result.files.single.path;
    if (selectedPath == null || selectedPath.isEmpty) {
      return;
    }

    if (!mounted) {
      return;
    }

    setState(() {
      _selectedPath = selectedPath;
    });
  }

  Future<bool> _ensureStoragePermission() async {
    if (!Platform.isAndroid) {
      return true;
    }

    PermissionStatus storageStatus = await Permission.storage.status;
    PermissionStatus manageStatus =
        await Permission.manageExternalStorage.status;
    if (storageStatus.isGranted || manageStatus.isGranted) {
      return true;
    }

    storageStatus = await Permission.storage.request();
    manageStatus = await Permission.manageExternalStorage.request();
    final bool granted = storageStatus.isGranted || manageStatus.isGranted;
    if (granted) {
      return true;
    }

    if (!mounted) {
      return false;
    }

    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Storage permission is required to select APK files.'),
      ),
    );

    if (storageStatus.isPermanentlyDenied || manageStatus.isPermanentlyDenied) {
      await openAppSettings();
    }

    return false;
  }

  void _clearSelection() {
    if (_selectedPath == null) {
      return;
    }

    setState(() {
      _selectedPath = null;
    });
  }

  Future<bool> _cleanupPickedFile(String path) async {
    if (!Platform.isAndroid) {
      return false;
    }

    if (!path.contains('/cache/file_picker/')) {
      return false;
    }

    try {
      await FilePicker.platform.clearTemporaryFiles();
      final File file = File(path);
      if (await file.exists()) {
        await file.delete();
        final Directory parentDir = file.parent;
        if (parentDir.path.contains('/cache/file_picker/')) {
          await parentDir.delete(recursive: true);
        }
        return true;
      }
    } catch (_) {
      return false;
    }

    return false;
  }

  Future<void> _installSelectedApk() async {
    final String? selectedPath = _selectedPath;
    if (selectedPath == null || _isInstalling || !_isShizukuReady) {
      if (!_isShizukuReady && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Shizuku is not ready for installation yet.'),
          ),
        );
      }
      return;
    }

    setState(() {
      _isInstalling = true;
    });

    bool isSuccess = false;
    bool isCancelled = false;
    String? failureMessage;
    try {
      final String lowerName = selectedPath.toLowerCase().split('/').last;
      final int dotIndex = lowerName.lastIndexOf('.');
      final String extension = dotIndex > 0
          ? lowerName.substring(dotIndex + 1)
          : '';
      if (extension == 'xapk') {
        isSuccess = await ShizukuInstaller.installXapk(selectedPath);
      } else {
        isSuccess = await ShizukuInstaller.installApk(selectedPath);
      }
    } on PlatformException catch (error) {
      if (error.code == 'INSTALL_CANCELLED') {
        isCancelled = true;
      } else {
        failureMessage = error.message;
      }
    } catch (_) {
      isSuccess = false;
    }

    if (!mounted) {
      return;
    }

    setState(() {
      _isInstalling = false;
    });

    final String message = isSuccess
        ? 'Installation completed successfully!'
        : (isCancelled
              ? 'Installation cancelled.'
              : (failureMessage ?? 'Failed to install requested file...'));
    final bool cleaned = await _cleanupPickedFile(selectedPath);
    if (cleaned && mounted) {
      setState(() {
        _selectedPath = null;
      });
    }
    ScaffoldMessenger.of(
      // ignore: use_build_context_synchronously
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _cancelInstall() async {
    if (!_isInstalling) {
      return;
    }

    try {
      final bool didCancel = await ShizukuInstaller.cancelInstall();
      if (!mounted) {
        return;
      }
      if (!didCancel) {
        return;
      }
    } catch (_) {
      return;
    }
  }

  @override
  Widget build(BuildContext context) {
    final bool isDarkEffective = widget.themeMode == ThemeMode.system
        ? MediaQuery.of(context).platformBrightness == Brightness.dark
        : widget.themeMode == ThemeMode.dark;
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.surface,
        foregroundColor: Theme.of(context).colorScheme.onSurface,
        surfaceTintColor: Theme.of(context).colorScheme.surface,
        elevation: 0,
        scrolledUnderElevation: 0,
        title: Text(
          'Shizuku Package Installer',
          style: Theme.of(
            context,
          ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w600),
        ),
        actions: <Widget>[
          Row(
            children: <Widget>[
              const Icon(Icons.light_mode, size: 18),
              Switch.adaptive(
                value: isDarkEffective,
                onChanged: (bool nextValue) {
                  widget.onThemeModeChanged(
                    nextValue ? ThemeMode.dark : ThemeMode.light,
                  );
                },
              ),
              const Icon(Icons.dark_mode, size: 18),
              const SizedBox(width: 8),
            ],
          ),
        ],
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: LayoutBuilder(
            builder: (context, constraints) {
              final bool isLandscape =
                  MediaQuery.of(context).orientation == Orientation.landscape;
              final double maxWidth = isLandscape ? 900 : 560;
              final Widget content = isLandscape
                  ? Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: <Widget>[
                        Expanded(
                          child: ShizukuStatusSection(
                            isChecking: _isCheckingShizuku,
                            shizukuState: _shizukuState,
                            isReady: _isShizukuReady,
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: FileSelectionSection(
                            selectedPath: _selectedPath,
                            isInstalling: _isInstalling,
                            isShizukuReady: _isShizukuReady,
                            hasValidSelection: _hasValidSelection,
                            onSelectFile: _openFileSelector,
                            onInstall: _installSelectedApk,
                            onClearSelection: _clearSelection,
                            onCancelInstall: _cancelInstall,
                          ),
                        ),
                      ],
                    )
                  : Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: <Widget>[
                        ShizukuStatusSection(
                          isChecking: _isCheckingShizuku,
                          shizukuState: _shizukuState,
                          isReady: _isShizukuReady,
                        ),
                        const SizedBox(height: 16),
                        FileSelectionSection(
                          selectedPath: _selectedPath,
                          isInstalling: _isInstalling,
                          isShizukuReady: _isShizukuReady,
                          hasValidSelection: _hasValidSelection,
                          onSelectFile: _openFileSelector,
                          onInstall: _installSelectedApk,
                          onClearSelection: _clearSelection,
                          onCancelInstall: _cancelInstall,
                        ),
                      ],
                    );

              return ConstrainedBox(
                constraints: BoxConstraints(maxWidth: maxWidth),
                child: Card(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: content,
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}
