package com.zubair.shizukupackageinstaller

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import com.zubair.shizukupackageinstaller.installer.PackageInstaller
import com.zubair.shizukupackageinstaller.privileged.ProviderSelector
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
  companion object {
    private const val CHANNEL = "adb_pkg_installer/shizuku"
    private const val TAG = "ShizukuInstaller"
  }

  private lateinit var channel: MethodChannel
  private val supportedApkExtensions = setOf("apk")
  private val supportedXapkExtensions = setOf("xapk")
  private val ioExecutor = Executors.newSingleThreadExecutor()
  private var pendingUri: Uri? = null
  private lateinit var providerSelector: ProviderSelector
  private lateinit var packageInstaller: PackageInstaller

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    providerSelector = ProviderSelector(this)
    providerSelector.initialize()
    packageInstaller = PackageInstaller(this, providerSelector) { action -> runOnUiThread(action) }
    handleIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
  }

  private fun getStreamUri(intent: Intent): Uri? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
      @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }
  }

  private fun handleIntent(intent: Intent) {
    val uri =
            when (intent.action) {
              Intent.ACTION_VIEW -> intent.data
              Intent.ACTION_SEND -> getStreamUri(intent)
              else -> null
            }

    if (uri != null) {
      pendingUri = uri

      if (::channel.isInitialized) {
        pendingUri = null
        channel.invokeMethod("onOpenFile", uri.toString())
      }
    }
  }

  override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)

    channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
    channel.setMethodCallHandler { call, result,
      ->
      when (call.method) {
        "getPendingUri" -> {
          result.success(pendingUri?.toString())
          pendingUri = null
        }
        "copyUriToTempDir" -> {
          val uriString = call.argument<String>("uriString")

          if (uriString.isNullOrBlank()) {
            result.error("INVALID_URI", "uriString is required.", null)
            return@setMethodCallHandler
          }
          copyUriToTempDir(uriString, result)
        }
        "getShizukuState" -> {
          result.success(providerSelector.getStatePayload())
        }
        "installApk" -> {
          val apkPath = call.argument<String>("apkPath")
          if (apkPath.isNullOrBlank()) {
            result.error("INVALID_APK_PATH", "apkPath is required.", null)
            return@setMethodCallHandler
          }

          packageInstaller.installApk(apkPath, result)
        }
        "installXapk" -> {
          val xapkPath = call.argument<String>("xapkPath")
          if (xapkPath.isNullOrBlank()) {
            result.error("INVALID_XAPK_PATH", "xapkPath is required.", null)
            return@setMethodCallHandler
          }

          packageInstaller.installXapk(xapkPath, result)
        }
        "cancelInstall" -> {
          packageInstaller.cancelInstall(result)
        }
        else -> result.notImplemented()
      }
    }
  }

  private fun copyUriToTempDir(uriString: String, result: MethodChannel.Result) {
    ioExecutor.execute {
      val uri = Uri.parse(uriString)
      var fileName: String? = null

      contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
              cursor ->
        if (cursor.moveToFirst()) {
          val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index >= 0) {
            fileName = cursor.getString(index)
          }
        }
      }

      if (fileName.isNullOrBlank()) {
        runOnUiThread {
          Log.w(TAG, "copyUriToTempDir rejected: unable to determine filename")
          result.error("MISSING_FILENAME", "Unable to determine file name of selected file", null)
        }
        return@execute
      }

      val extension = File(fileName!!).extension.lowercase()
      if (extension !in supportedApkExtensions && extension !in supportedXapkExtensions) {
        runOnUiThread {
          Log.w(TAG, "copyUriToTempDir rejected: unsupported extension: .$extension", null)
          result.error("UNSUPPORTED_EXTENSION", "Unsupported file type: .$extension", null)
        }
        return@execute
      }

      val destination = File(cacheDir, fileName!!)
      try {
        contentResolver.openInputStream(uri)?.use { input ->
          destination.outputStream().use { output -> input.copyTo(output) }
        }
                ?: run {
                  runOnUiThread {
                    result.error("FAILED_TO_OPEN_URI", "Unable to open the selected file.", null)
                  }
                  return@execute
                }
      } catch (e: IOException) {
        runOnUiThread {
          Log.e(TAG, "Failed copying URI, an IO exception is encountered.", e)
          result.error("COPY_FAILED", e.message, null)
        }
        destination.delete()
        return@execute
      } catch (e: Exception) {
        runOnUiThread {
          Log.e(TAG, "Unexpected exception caught while copying file to temp path.", e)
          result.error(
                  "COPY_FAILED",
                  "Unexpected error occurred while copying file to temporary path.",
                  null
          )
        }
        destination.delete()
        return@execute
      }

      runOnUiThread { result.success(destination.absolutePath) }
    }
  }

  override fun onDestroy() {
    providerSelector.close()
    super.onDestroy()
  }
}
