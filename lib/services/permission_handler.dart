import 'dart:io';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

class PermissionHandler {
  static Future<bool> ensureStoragePermission(BuildContext context) async {
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

    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Storage permission is required to select APK files.'),
        ),
      );
    }

    if (storageStatus.isPermanentlyDenied || manageStatus.isPermanentlyDenied) {
      await openAppSettings();
    }

    return false;
  }
}
