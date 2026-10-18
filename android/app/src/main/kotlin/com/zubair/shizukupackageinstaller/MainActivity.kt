package com.zubair.shizukupackageinstaller

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class MainActivity : FlutterActivity() {
  companion object {
    private const val CHANNEL = "adb_pkg_installer/shizuku"
    private const val TAG = "ShizukuInstaller"
    private val SHIZUKU_MANAGER_PACKAGES =
            arrayOf(
                    "moe.shizuku.privileged.api",
                    "moe.shizuku.manager",
            )
  }

  private lateinit var channel: MethodChannel
  private var binderAvailable = false
  private val supportedApkExtensions = setOf("apk")
  private val supportedXapkExtensions = setOf("xapk")
  private val ioExecutor = Executors.newSingleThreadExecutor()
  private var pendingUri: Uri? = null
  private lateinit var packageInstaller: PackageInstaller
  private val privilegedApi =
          object : PrivilegedApi {
            override fun isAvailable(): Boolean {
              return binderAvailable || isShizukuBinderAlive()
            }

            override fun hasPermission(): Boolean {
              return try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
              } catch (_: Throwable) {
                false
              }
            }

            override fun createProcess(
                    command: Array<String>,
                    env: Array<String>?,
                    dir: String?,
            ): Process? {
              return try {
                val method =
                        Shizuku::class.java.getDeclaredMethod(
                                "newProcess",
                                Array<String>::class.java,
                                Array<String>::class.java,
                                String::class.java,
                        )
                method.isAccessible = true
                method.invoke(null, command, env, dir) as? Process
              } catch (error: Throwable) {
                Log.w(TAG, "Failed to create Shizuku process: ${error.message}")
                null
              }
            }
          }

  private val binderReceivedListener =
          Shizuku.OnBinderReceivedListener {
            binderAvailable = true
            Log.d(TAG, "Shizuku binder connected")
          }

  private val binderDeadListener =
          Shizuku.OnBinderDeadListener {
            binderAvailable = false
            Log.d(TAG, "Shizuku binder disconnected")
          }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    packageInstaller = PackageInstaller(this, privilegedApi) { action -> runOnUiThread(action) }
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

    requestShizukuBinderIfNeeded()
    binderAvailable = isShizukuBinderAlive()
    Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
    Shizuku.addBinderDeadListener(binderDeadListener)

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
          result.success(getShizukuStatePayload())
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

  private fun getShizukuStatePayload(): Map<String, Any> {
    requestShizukuBinderIfNeeded()
    val running = binderAvailable || isShizukuBinderAlive()
    Log.d(TAG, "Shizuku state check: running=$running, binderAvailable=$binderAvailable")
    if (running) {
      binderAvailable = true
      val permissionGranted =
              try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
              } catch (_: Throwable) {
                false
              }

      return if (permissionGranted) {
        mapOf(
                "status" to "ready",
                "message" to "Shizuku is ready.",
                "installed" to true,
                "running" to true,
                "permissionGranted" to true,
        )
      } else {
        mapOf(
                "status" to "running_no_permission",
                "message" to "Shizuku is running but permission is not granted.",
                "installed" to true,
                "running" to true,
                "permissionGranted" to false,
        )
      }
    }

    val installed = isAnyPackageInstalled(SHIZUKU_MANAGER_PACKAGES)
    if (!installed) {
      return mapOf(
              "status" to "manager_not_installed",
              "message" to "Shizuku manager is not installed.",
              "installed" to false,
              "running" to false,
              "permissionGranted" to false,
      )
    }

    return mapOf(
            "status" to "installed_not_running",
            "message" to "Shizuku is installed but not running.",
            "installed" to true,
            "running" to false,
            "permissionGranted" to false,
    )
  }

  private fun isShizukuBinderAlive(): Boolean {
    return try {
      val ping = Shizuku.pingBinder()
      val binderAlive = Shizuku.getBinder()?.isBinderAlive == true
      ping || binderAlive
    } catch (_: Throwable) {
      false
    }
  }

  private fun requestShizukuBinderIfNeeded() {
    if (binderAvailable) {
      return
    }

    try {
      ShizukuProvider.requestBinderForNonProviderProcess(this)
      Log.d(TAG, "Requested Shizuku binder from provider")
    } catch (error: Throwable) {
      Log.w(TAG, "Failed requesting Shizuku binder: ${error.message}")
    }
  }

  private fun isAnyPackageInstalled(packageNames: Array<String>): Boolean {
    for (packageName in packageNames) {
      if (isPackageInstalled(packageName)) {
        return true
      }
    }
    return false
  }

  private fun isPackageInstalled(packageName: String): Boolean {
    return try {
      packageManager.getPackageInfo(packageName, 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    } catch (_: Throwable) {
      false
    }
  }

  override fun onDestroy() {
    Shizuku.removeBinderReceivedListener(binderReceivedListener)
    Shizuku.removeBinderDeadListener(binderDeadListener)
    super.onDestroy()
  }
}
