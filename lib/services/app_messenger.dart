import 'package:flutter/material.dart';

class AppMessenger {
  static final messengerKey = GlobalKey<ScaffoldMessengerState>();

  static void showSnackBar(SnackBar snackBar) {
    final state = messengerKey.currentState;
    state
      ?..hideCurrentSnackBar()
      ..showSnackBar(snackBar);
  }

  static void showMessage(String msg, {Duration? duration}) {
    showSnackBar(
      SnackBar(
        content: Text(msg),
        duration: duration ?? const Duration(seconds: 4),
      ),
    );
  }
}
