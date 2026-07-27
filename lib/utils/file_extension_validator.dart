class FileExtensionValidator {
  static const allowedExtensions = {'apk', 'xapk'};

  static String getExtension(String path) {
    return path.split('.').last.toLowerCase();
  }

  static bool isValid(String path) {
    return allowedExtensions.contains(getExtension(path));
  }
}
