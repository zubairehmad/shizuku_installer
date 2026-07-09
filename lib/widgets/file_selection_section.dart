import 'package:flutter/material.dart';

import './header_widget.dart';

class FileSelectionSection extends StatelessWidget {
  const FileSelectionSection({
    super.key,
    required this.selectedPath,
    required this.isInstalling,
    required this.isShizukuReady,
    required this.isFileLoading,
    required this.hasValidSelection,
    required this.onSelectFile,
    required this.onInstall,
    required this.onClearSelection,
    required this.onCancelInstall,
  });

  final String? selectedPath;
  final bool isInstalling;
  final bool isShizukuReady;
  final bool hasValidSelection;
  final bool isFileLoading;
  final VoidCallback onSelectFile;
  final VoidCallback onInstall;
  final VoidCallback onClearSelection;
  final VoidCallback onCancelInstall;

  @override
  Widget build(BuildContext context) {
    final bool hasSelection = selectedPath != null;
    final bool isSelectDisabled = isInstalling || !isShizukuReady;
    final bool canInstall = isShizukuReady && hasSelection && !isInstalling;
    final HeaderStatus headerStatus = !isShizukuReady
        ? HeaderStatus.notReady
        : (hasValidSelection
              ? HeaderStatus.ready
              : HeaderStatus.oneStepRequired);
    final String selectLabel = selectedPath != null
        ? 'Change Selected File'
        : 'Select Installable File';
    final VoidCallback? installHandler = canInstall && hasValidSelection
        ? onInstall
        : null;
    final VoidCallback? clearHandler = (!hasSelection || isInstalling)
        ? null
        : onClearSelection;

    final String? fileName = hasSelection
        ? selectedPath!.split('/').last
        : null;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        HeaderWidget(header: 'Install File', status: headerStatus),
        const SizedBox(height: 8),
        if (isFileLoading) ...const [
          Text('Please wait, file is being loaded'),
          SizedBox(height: 16),
          LinearProgressIndicator(),
        ] else ...[
          Text(
            fileName ?? 'No file selected.',
            maxLines: 4,
            overflow: TextOverflow.ellipsis,
          ),
          const SizedBox(height: 16),
          ElevatedButton.icon(
            onPressed: isSelectDisabled ? null : onSelectFile,
            icon: const Icon(Icons.folder_open),
            label: Text(selectLabel),
          ),
        ],
        const SizedBox(height: 10),
        ElevatedButton.icon(
          onPressed: installHandler,
          icon: const Icon(Icons.download),
          label: Text(isInstalling ? 'Installing...' : 'Download'),
        ),
        if (isInstalling) ...<Widget>[
          const SizedBox(height: 12),
          const LinearProgressIndicator(),
          const SizedBox(height: 10),
          const Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: <Widget>[
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
            onPressed: onCancelInstall,
            icon: const Icon(Icons.stop_circle_outlined),
            label: const Text('Cancel Install'),
          ),
        ],
        const SizedBox(height: 8),
        TextButton.icon(
          onPressed: clearHandler,
          icon: const Icon(Icons.clear),
          label: const Text('Clear Selection'),
        ),
      ],
    );
  }
}
