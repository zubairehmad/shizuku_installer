import 'package:flutter/material.dart';

enum HeaderStatus { ready, oneStepRequired, notReady, loading }

class HeaderWidget extends StatelessWidget {
  const HeaderWidget({super.key, required this.header, required this.status});

  final String header;
  final HeaderStatus status;

  @override
  Widget build(BuildContext context) {
    late final MaterialColor background;
    late final IconData iconData;

    switch (status) {
      case HeaderStatus.ready:
        background = Colors.green;
        iconData = Icons.check;
      case HeaderStatus.oneStepRequired:
        background = Colors.amber;
        iconData = Icons.priority_high;
      case HeaderStatus.notReady:
        background = Colors.red;
        iconData = Icons.close;
      default:
        // initlaizing because required, but they won't be used
        background = Colors.red;
        iconData = Icons.close;
    }

    return Row(
      children: [
        Row(
          children: [
            Text(header, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(width: 8),
            if (status == HeaderStatus.loading)
              SizedBox(
                height: 18,
                width: 18,
                child: const CircularProgressIndicator(strokeWidth: 2.2),
              )
            else
              CircleAvatar(
                radius: 10,
                backgroundColor: background,
                foregroundColor: Colors.white,
                child: Icon(iconData, size: 16),
              ),
          ],
        ),
      ],
    );
  }
}
