import 'package:flutter/material.dart';

import '../models/shizuku_state_info.dart';
import './header_widget.dart';

class ShizukuStatusSection extends StatelessWidget {
  const ShizukuStatusSection({
    super.key,
    required this.isChecking,
    required this.shizukuState,
    required this.isReady,
  });

  final bool isChecking;
  final ShizukuStateInfo? shizukuState;
  final bool isReady;

  @override
  Widget build(BuildContext context) {
    final message = shizukuState?.message ?? 'Unknown Shizuku state.';
    final status = shizukuState?.status;
    final HeaderStatus headerStatus;
    if (isReady) {
      headerStatus = HeaderStatus.ready;
    } else if (status == 'unsupported' || status == 'manager_not_installed') {
      headerStatus = HeaderStatus.notReady;
    } else {
      headerStatus = HeaderStatus.oneStepRequired;
    }

    final Widget statusContent = isChecking
        ? const Row(
            children: <Widget>[
              SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2.1),
              ),
              SizedBox(width: 8),
              Expanded(child: Text('Checking Shizuku status...')),
            ],
          )
        : Text(message);

    Widget? action;
    if (!isChecking && status == 'running_no_permission') {
      action = const Text(
        'Shizuku permission not granted. Please grant permission in Shizuku settings.',
        textAlign: TextAlign.left,
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        HeaderWidget(
          header: 'Shizuku Status',
          status: headerStatus,
        ),
        const SizedBox(height: 8),
        statusContent,
        const SizedBox(height: 8),
        if (action != null) action,
      ],
    );
  }
}
