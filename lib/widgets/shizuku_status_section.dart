import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../providers/shizuku_state_provider.dart';
import 'header_widget.dart';

class ShizukuStatusSection extends StatelessWidget {
  const ShizukuStatusSection({super.key});

  @override
  Widget build(BuildContext context) {
    final stateProvider = context.watch<ShizukuStateProvider>();
    final message = stateProvider.statusMessage ?? 'Unknown Shizuku state.';
    final HeaderStatus headerStatus;

    switch (stateProvider.status) {
      case ShizukuStatus.ready:
        headerStatus = HeaderStatus.ready;
        break;
      case ShizukuStatus.notRunning:
      case ShizukuStatus.permissionRequired:
        headerStatus = HeaderStatus.oneStepRequired;
        break;
      default:
        headerStatus = HeaderStatus.notReady;
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        HeaderWidget(header: 'Shizuku Status', status: headerStatus),
        const SizedBox(height: 8),
        Text(message),
        const SizedBox(height: 8),
      ],
    );
  }
}
