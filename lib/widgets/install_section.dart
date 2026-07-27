import 'dart:io';

import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../utils/file_extension_validator.dart';
import '../services/app_messenger.dart';
import '../services/shizuku_installer.dart';
import '../services/permission_handler.dart';
import '../providers/shizuku_state_provider.dart';
import 'header_widget.dart';

class InstallSection extends StatefulWidget {
  const InstallSection({super.key});

  @override
  State<InstallSection> createState() => _InstallSectionState();
}

class _InstallSectionState extends State<InstallSection> {
  String? _selectedPath;
  bool _isFileLoading = false;
  bool _isInstalling = false;
  bool _cancelRequested = false;
  bool _isSelectedByOpenWith = false;

  Future<void> _handlePendingUri() async {
    final uri = await ShizukuInstaller.getPendingUri();
    if (uri != null) {
      _handleUriInstallRequest(uri);
    }
  }

  Future<void> _handleUriInstallRequest(String uri) async {
    if (_isInstalling) {
      return;
    }

    setState(() => _isFileLoading = true);

    try {
      final path = await ShizukuInstaller.copyUriToTempDir(uri);
      _selectedPath = path;
      _isSelectedByOpenWith = true;
    } on PlatformException catch (e) {
      AppMessenger.showMessage(
        "${e.code} ${e.message == null || e.message!.isEmpty ? '' : "- ${e.message}"}",
      );
    } catch (e) {
      debugPrint(
        "Unexpected exception caught in _handleUriInstallRequest: ${e.toString()}",
      );
      AppMessenger.showMessage(
        "Unexpected error occured, please try again later.",
      );
    }

    setState(() => _isFileLoading = false);
  }

  @override
  void initState() {
    super.initState();
    _handlePendingUri();
    ShizukuInstaller.setOnOpenFileListener(listener: _handleUriInstallRequest);
  }

  Future<void> _installSelectedPackage() async {
    setState(() {
      _isInstalling = true;
    });

    bool isSuccess = false;
    bool isCancelled = false;
    String? failureMessage;

    try {
      final extension = FileExtensionValidator.getExtension(_selectedPath!);
      if (extension == 'xapk') {
        isSuccess = await ShizukuInstaller.installXapk(_selectedPath!);
      } else {
        isSuccess = await ShizukuInstaller.installApk(_selectedPath!);
      }
    } on PlatformException catch (error) {
      if (error.code == 'INSTALL_CANCELLED') {
        isCancelled = true;
      } else {
        failureMessage = error.message;
      }
    } catch (e) {
      debugPrint(
        'Unexpected exception caught while installing package: ${e.toString()}',
      );
      isSuccess = false;
    }

    if (mounted) {
      setState(() {
        _isInstalling = false;
        _cancelRequested = false;
      });
    }

    final String message = isSuccess
        ? 'Installation completed successfully!'
        : (isCancelled
              ? 'Installation cancelled.'
              : (failureMessage ?? 'Failed to install requested file...'));

    AppMessenger.showMessage(message);
    _clearSelection();
  }

  Future<void> _openFileSelector() async {
    if (!(await PermissionHandler.ensureStoragePermission(context))) {
      return;
    }

    if (!mounted) return;

    setState(() => _isFileLoading = true);
    try {
      final result = await FilePicker.platform.pickFiles(
        allowedExtensions: FileExtensionValidator.allowedExtensions.toList(),
        type: FileType.custom,
      );

      final selectedPath = result?.files.singleOrNull?.path;
      if (!mounted || selectedPath == null || selectedPath.isEmpty) {
        return;
      }

      setState(() {
        _selectedPath = selectedPath;
        _isSelectedByOpenWith = false;
        _isFileLoading = false;
      });
    } catch (e) {
      debugPrint('Exception caught while selecting file: ${e.toString()}');
    } finally {
      if (mounted && _isFileLoading) setState(() => _isFileLoading = false);
    }
  }

  Future<bool> _cleanupPickedFile(String path) async {
    if (!Platform.isAndroid) {
      return false;
    }

    if (!path.contains('/cache/file_picker/') && !_isSelectedByOpenWith) {
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
    } catch (e) {
      debugPrint(
        'Unexpected exception caught while cleanup of temporary file "$path": ${e.toString()}',
      );
    }

    return false;
  }

  Future<void> _cancelInstall() async {
    if (!_isInstalling) {
      return;
    }

    setState(() => _cancelRequested = true);

    try {
      await ShizukuInstaller.cancelInstall();
    } on PlatformException catch (e) {
      AppMessenger.showMessage(
        'Failed to cancel: ${e.code} ${e.message == null ? '' : "- ${e.message}"}',
        duration: const Duration(seconds: 6),
      );
      setState(() => _cancelRequested = false);
    } catch (_) {
      AppMessenger.showMessage(
        'Unexpected error occured while trying to cancel',
      );
      setState(() => _cancelRequested = false);
    }
  }

  Future<void> _clearSelection() async {
    final future = _cleanupPickedFile(_selectedPath!);
    setState(() {
      _selectedPath = null;
      _isSelectedByOpenWith = false;
    });
    await future;
  }

  @override
  Widget build(BuildContext context) {
    return Selector<ShizukuStateProvider, bool>(
      selector: (_, shizukuState) => shizukuState.isReady,
      builder: (_, isShizukuReady, _) {
        final hasSelection = _selectedPath != null;
        final isSelectDisabled = _isInstalling || !isShizukuReady;
        final canClearSelection = hasSelection && !_isInstalling && !_isFileLoading;
        final canInstall = isShizukuReady && canClearSelection;
        final fileName = _selectedPath?.split('/').last;
        final selectLabel = _selectedPath != null
            ? 'Change Selected File'
            : 'Select Installable File';
        final headerStatus = !isShizukuReady
            ? HeaderStatus.notReady
            : hasSelection
            ? HeaderStatus.ready
            : HeaderStatus.oneStepRequired;
        final installationText = _isInstalling
            ? _cancelRequested
                  ? 'Cancelling...'
                  : 'Installing...'
            : 'Install';

        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            HeaderWidget(header: 'Install File', status: headerStatus),
            const SizedBox(height: 8),
            if (_isFileLoading) ...const [
              Text('Please wait, file is being loaded'),
              SizedBox(height: 16),
              LinearProgressIndicator(),
            ] else ...[
              Text(
                fileName ?? 'No file selected.',
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              const SizedBox(height: 16),
              ElevatedButton.icon(
                onPressed: isSelectDisabled ? null : _openFileSelector,
                icon: const Icon(Icons.folder_open),
                label: Text(selectLabel),
              ),
            ],
            const SizedBox(height: 10),
            ElevatedButton.icon(
              onPressed: canInstall ? _installSelectedPackage : null,
              icon: const Icon(Icons.download),
              label: Text(installationText),
            ),
            if (_isInstalling) ...[
              const SizedBox(height: 12),
              if (_cancelRequested) ...[
                const Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2.2),
                    ),
                    SizedBox(width: 10),
                    Flexible(
                      child: Text(
                        'Cancelling installation...',
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ],
                ),
              ] else ...[
                const LinearProgressIndicator(),
                const SizedBox(height: 10),
                const Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2.2),
                    ),
                    SizedBox(width: 10),
                    Flexible(
                      child: Text(
                        'Please wait while we are installing the application.',
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  onPressed: _cancelInstall,
                  icon: const Icon(Icons.stop_circle_outlined),
                  label: const Text('Cancel Install'),
                ),
              ],
            ],
            const SizedBox(height: 8),
            TextButton.icon(
              onPressed: canClearSelection ? _clearSelection : null,
              icon: const Icon(Icons.clear),
              label: const Text('Clear Selection'),
            ),
          ],
        );
      },
    );
  }
}
