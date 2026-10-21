import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../providers/privileged_access_state_provider.dart';
import 'header_widget.dart';

class PrivilegedAccessStatusSection extends StatelessWidget {
  const PrivilegedAccessStatusSection({super.key});

  @override
  Widget build(BuildContext context) {
    final stateProvider = context.watch<PrivilegedAccessStateProvider>();
    final selectedProvider =
        stateProvider.stateInfo?.selectedProvider ?? 'Shizuku/Porter';
    final message =
        stateProvider.statusMessage ?? 'Unknown privileged access state.';
    final HeaderStatus headerStatus;

    switch (stateProvider.status) {
      case PrivilegedAccessStatus.notInitialized:
        headerStatus = HeaderStatus.loading;
        break;
      case PrivilegedAccessStatus.ready:
        headerStatus = HeaderStatus.ready;
        break;
      case PrivilegedAccessStatus.notRunning:
      case PrivilegedAccessStatus.permissionRequired:
      case PrivilegedAccessStatus.notReady:
        headerStatus = HeaderStatus.oneStepRequired;
        break;
      default:
        headerStatus = HeaderStatus.notReady;
    }

    final isLoading = headerStatus == HeaderStatus.loading;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        HeaderWidget(header: "$selectedProvider Status", status: headerStatus),
        const SizedBox(height: 8),
        Text(isLoading ? "Checking privileged access status..." : message),
        const SizedBox(height: 8),
      ],
    );
  }
}
