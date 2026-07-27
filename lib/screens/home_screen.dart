import 'package:flutter/material.dart';

import '../widgets/install_section.dart';
import '../widgets/shizuku_status_section.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({
    super.key,
    required this.themeMode,
    required this.onThemeModeChanged,
  });

  final ThemeMode themeMode;
  final ValueChanged<ThemeMode> onThemeModeChanged;

  @override
  Widget build(BuildContext context) {
    final bool isDarkMode = themeMode == ThemeMode.system
        ? MediaQuery.of(context).platformBrightness == Brightness.dark
        : themeMode == ThemeMode.dark;

    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Shizuku Package Installer',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        actions: [
          Row(
            children: [
              const Icon(Icons.light_mode, size: 18),
              Switch.adaptive(
                value: isDarkMode,
                onChanged: (bool nextValue) {
                  onThemeModeChanged(
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
              final isLandscape =
                  MediaQuery.of(context).orientation == Orientation.landscape;
              final double maxWidth = isLandscape ? 900 : 560;
              final content = isLandscape
                  ? const Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(child: ShizukuStatusSection()),
                        SizedBox(width: 16),
                        Expanded(child: InstallSection()),
                      ],
                    )
                  : const Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        ShizukuStatusSection(),
                        SizedBox(height: 16),
                        InstallSection(),
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
