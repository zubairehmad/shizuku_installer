package com.zubair.shizukupackageinstaller

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.json.JSONObject
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
  @Volatile private var installInProgress = false
  @Volatile private var cancelRequested = false
  @Volatile private var activeProcess: Process? = null
  @Volatile private var activeStagedPaths: MutableList<String>? = null
  @Volatile private var activeExtractedDir: File? = null
  private val installLock = Any()
  private var pendingUri: Uri? = null

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

          installApkWithShizuku(apkPath, result)
        }
        "installXapk" -> {
          val xapkPath = call.argument<String>("xapkPath")
          if (xapkPath.isNullOrBlank()) {
            result.error("INVALID_XAPK_PATH", "xapkPath is required.", null)
            return@setMethodCallHandler
          }

          installXapkWithShizuku(xapkPath, result)
        }
        "cancelInstall" -> {
          cancelInstallWithShizuku(result)
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

  private fun installApkWithShizuku(apkPath: String, result: MethodChannel.Result) {
    if (installInProgress) {
      Log.w(TAG, "installApk rejected: install already in progress")
      result.error("INSTALL_IN_PROGRESS", "Another install is already running.", null)
      return
    }

    resetInstallState()

    val file = File(apkPath)
    if (!file.exists() || !file.isFile) {
      Log.w(TAG, "installApk rejected: file missing: $apkPath")
      result.error("APK_NOT_FOUND", "File does not exist: $apkPath", null)
      return
    }

    val extension = file.extension.lowercase()
    if (!supportedApkExtensions.contains(extension)) {
      Log.w(TAG, "installApk rejected: unsupported extension: .$extension")
      result.error("APK_UNSUPPORTED", "Unsupported file type: .$extension", null)
      return
    }

    val running = binderAvailable || isShizukuBinderAlive()
    if (!running) {
      Log.w(TAG, "installApk rejected: Shizuku not running")
      result.error("SHIZUKU_NOT_RUNNING", "Shizuku is not running.", null)
      return
    }

    val hasPermission =
            try {
              Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
              false
            }

    if (!hasPermission) {
      Log.w(TAG, "installApk rejected: Shizuku permission not granted")
      result.error("SHIZUKU_PERMISSION", "Shizuku permission not granted.", null)
      return
    }

    installInProgress = true
    Thread {
              var stagedPath: String? = null
              try {
                throwIfCancelled()
                Log.d(TAG, "installApk called with path: $apkPath")
                stagedPath = stageApkToShellTmp(apkPath, System.currentTimeMillis().toString())
                Log.d(TAG, "Staged APK path (/data/local/tmp): $stagedPath")
                setActiveCleanup(mutableListOf(stagedPath), null)
                val command = "pm install -r --user 0 \"$stagedPath\""
                throwIfCancelled()
                val process =
                        createShizukuProcess(arrayOf("sh", "-c", command), null, null)
                                ?: throw IllegalStateException("Unable to create Shizuku process")
                setActiveProcess(process)
                Log.d(TAG, "Shizuku install process started")
                val exitCode = waitForExitCode(process, 90, "installApk")
                val output = readAll(process.inputStream)
                val errorOutput = readAll(process.errorStream)
                val success = exitCode == 0
                val message = errorOutput.ifBlank { output }
                Log.d(
                        TAG,
                        "installApk exit=$exitCode, stdout=${output.trim()}, stderr=${errorOutput.trim()}",
                )
                runOnUiThread {
                  installInProgress = false
                  if (success) {
                    result.success(true)
                  } else {
                    result.error("APK_INSTALL_FAILED", message.ifBlank { "Install failed." }, null)
                  }
                }
              } catch (error: InstallCancelledException) {
                Log.w(TAG, "Install cancelled")
                runOnUiThread {
                  installInProgress = false
                  result.error("INSTALL_CANCELLED", "Install cancelled.", null)
                }
              } catch (error: Throwable) {
                if (cancelRequested) {
                  Log.w(TAG, "Install cancelled during failure: ${error.message}")
                  runOnUiThread {
                    installInProgress = false
                    result.error("INSTALL_CANCELLED", "Install cancelled.", null)
                  }
                } else {
                  Log.w(TAG, "Install failed: ${error.message}")
                  runOnUiThread {
                    installInProgress = false
                    result.error(
                            "APK_INSTALL_FAILED",
                            error.message ?: "Install failed.",
                            null,
                    )
                  }
                }
              } finally {
                stagedPath?.let { cleanupStagedApk(it) }
                clearInstallState()
              }
            }
            .start()
  }

  private data class XapkPayload(
          val tempDir: File,
          val apkFiles: List<File>,
          val obbFiles: List<File>,
          val packageName: String?,
  )

  private fun installXapkWithShizuku(xapkPath: String, result: MethodChannel.Result) {
    if (installInProgress) {
      Log.w(TAG, "installXapk rejected: install already in progress")
      result.error("INSTALL_IN_PROGRESS", "Another install is already running.", null)
      return
    }

    resetInstallState()

    val file = File(xapkPath)
    if (!file.exists() || !file.isFile) {
      Log.w(TAG, "installXapk rejected: file missing: $xapkPath")
      result.error("XAPK_NOT_FOUND", "File does not exist: $xapkPath", null)
      return
    }

    val extension = file.extension.lowercase()
    if (!supportedXapkExtensions.contains(extension)) {
      Log.w(TAG, "installXapk rejected: unsupported extension: .$extension")
      result.error("XAPK_UNSUPPORTED", "Unsupported file type: .$extension", null)
      return
    }

    val running = binderAvailable || isShizukuBinderAlive()
    if (!running) {
      Log.w(TAG, "installXapk rejected: Shizuku not running")
      result.error("SHIZUKU_NOT_RUNNING", "Shizuku is not running.", null)
      return
    }

    val hasPermission =
            try {
              Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
              false
            }

    if (!hasPermission) {
      Log.w(TAG, "installXapk rejected: Shizuku permission not granted")
      result.error("SHIZUKU_PERMISSION", "Shizuku permission not granted.", null)
      return
    }

    installInProgress = true
    Thread {
              var payload: XapkPayload? = null
              val stagedPaths = mutableListOf<String>()
              try {
                throwIfCancelled()
                Log.d(TAG, "installXapk called with path: $xapkPath")
                payload = extractXapk(xapkPath)
                Log.d(TAG, "XAPK extracted to: ${payload.tempDir.absolutePath}")
                setActiveCleanup(stagedPaths, payload.tempDir)
                if (payload.apkFiles.isEmpty()) {
                  throw IllegalStateException("No APKs found inside XAPK.")
                }

                val packageName = payload.packageName ?: resolvePackageNameFromApk(payload.apkFiles)
                if (packageName.isNullOrBlank()) {
                  throw IllegalStateException("Unable to resolve package name from XAPK.")
                }

                payload.apkFiles.forEachIndexed { index, apkFile ->
                  throwIfCancelled()
                  Log.d(
                          TAG,
                          "Staging split APK ${index + 1}/${payload.apkFiles.size}: ${apkFile.name}"
                  )
                  val staged =
                          stageApkToShellTmp(
                                  apkFile.absolutePath,
                                  "${System.currentTimeMillis()}_$index",
                          )
                  stagedPaths.add(staged)
                  setActiveCleanup(stagedPaths, payload.tempDir)
                }

                throwIfCancelled()
                Log.d(TAG, "Running split install with ${stagedPaths.size} APK(s)")
                val installResult = runInstallMultipleCommand(stagedPaths)
                val exitCode = installResult.exitCode
                val success = exitCode == 0
                val message = installResult.errorOutput.ifBlank { installResult.output }
                Log.d(
                        TAG,
                        "installXapk exit=$exitCode, stdout=${installResult.output.trim()}, stderr=${installResult.errorOutput.trim()}",
                )
                throwIfCancelled()
                if (success) {
                  installObbFiles(packageName, payload.obbFiles)
                }
                runOnUiThread {
                  installInProgress = false
                  if (success) {
                    result.success(true)
                  } else {
                    result.error(
                            "XAPK_INSTALL_FAILED",
                            message.ifBlank { "Install failed." },
                            null,
                    )
                  }
                }
              } catch (error: InstallCancelledException) {
                Log.w(TAG, "XAPK install cancelled")
                runOnUiThread {
                  installInProgress = false
                  result.error("INSTALL_CANCELLED", "Install cancelled.", null)
                }
              } catch (error: Throwable) {
                Log.w(TAG, "XAPK install failed: ${error.message}")
                runOnUiThread {
                  installInProgress = false
                  result.error(
                          "XAPK_INSTALL_FAILED",
                          error.message ?: "Install failed.",
                          null,
                  )
                }
              } finally {
                stagedPaths.forEach { cleanupStagedApk(it) }
                payload?.let { cleanupExtractedXapk(it.tempDir) }
                clearInstallState()
              }
            }
            .start()
  }

  private fun cancelInstallWithShizuku(result: MethodChannel.Result) {
    try {
      val inProgress = installInProgress
      Log.d(
              TAG,
              "cancelInstall called: inProgress=$inProgress, " +
                      "hasProcess=${activeProcess != null}, " +
                      "stagedCount=${activeStagedPaths?.size ?: 0}",
      )
      if (!inProgress) {
        result.success(false)
        return
      }
      setCancelRequested()
      activeProcess?.destroy()
      activeStagedPaths?.toList()?.forEach { cleanupStagedApk(it) }
      activeExtractedDir?.let { cleanupExtractedXapk(it) }
      result.success(true)
    } catch (error: Throwable) {
      Log.w(TAG, "cancelInstall failed: ${error.message}")
      result.success(false)
    }
  }

  private fun resetInstallState() {
    synchronized(installLock) {
      cancelRequested = false
      activeProcess = null
      activeStagedPaths = null
      activeExtractedDir = null
    }
  }

  private fun clearInstallState() {
    synchronized(installLock) {
      activeProcess = null
      activeStagedPaths = null
      activeExtractedDir = null
      cancelRequested = false
    }
  }

  private fun setActiveProcess(process: Process) {
    synchronized(installLock) { activeProcess = process }
  }

  private fun setActiveCleanup(stagedPaths: MutableList<String>, extractedDir: File?) {
    synchronized(installLock) {
      activeStagedPaths = stagedPaths
      activeExtractedDir = extractedDir
    }
  }

  private fun setCancelRequested() {
    synchronized(installLock) { cancelRequested = true }
  }

  private fun throwIfCancelled() {
    if (cancelRequested) {
      throw InstallCancelledException()
    }
  }

  private fun extractXapk(xapkPath: String): XapkPayload {
    @Suppress("DEPRECATION")
    val downloadsDir =
            Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
            )
    val tempDir =
            File(
                    downloadsDir,
                    "adb_pkg_installer_xapk_${System.currentTimeMillis()}",
            )
    if (!tempDir.exists() && !tempDir.mkdirs()) {
      throw IllegalStateException("Unable to create temp directory for XAPK.")
    }

    val apkFiles = mutableListOf<File>()
    val obbFiles = mutableListOf<File>()
    var manifestPackage: String? = null

    ZipFile(xapkPath).use { zipFile ->
      val entries = zipFile.entries()
      while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (entry.isDirectory) {
          continue
        }
        val entryName = entry.name
        val lowerName = entryName.lowercase()
        val isManifest = lowerName == "manifest.json"
        val isApk = lowerName.endsWith(".apk")
        val isObb = lowerName.endsWith(".obb")
        if (!isManifest && !isApk && !isObb) {
          continue
        }

        if (isManifest) {
          zipFile.getInputStream(entry).use { input ->
            val manifestText = input.bufferedReader().readText()
            val manifest = JSONObject(manifestText)
            val packageFromManifest =
                    manifest.optString("package_name").ifBlank { manifest.optString("packageName") }
            if (packageFromManifest.isNotBlank()) {
              manifestPackage = packageFromManifest
            }
          }
        }

        val outFile = File(tempDir, entryName)
        if (!isSafeChildPath(tempDir, outFile)) {
          continue
        }
        outFile.parentFile?.mkdirs()
        zipFile.getInputStream(entry).use { input ->
          FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }

        if (isApk) {
          apkFiles.add(outFile)
        } else if (isObb) {
          obbFiles.add(outFile)
        }
      }
    }

    return XapkPayload(
            tempDir = tempDir,
            apkFiles = apkFiles,
            obbFiles = obbFiles,
            packageName = manifestPackage,
    )
  }

  private fun isSafeChildPath(parent: File, child: File): Boolean {
    return try {
      val parentPath = parent.canonicalFile.toPath()
      val childPath = child.canonicalFile.toPath()
      childPath.startsWith(parentPath)
    } catch (_: Throwable) {
      false
    }
  }

  private fun resolvePackageNameFromApk(apkFiles: List<File>): String? {
    if (apkFiles.isEmpty()) {
      return null
    }
    val baseApk =
            apkFiles.firstOrNull { it.name.contains("base", ignoreCase = true) } ?: apkFiles.first()
    return try {
      packageManager.getPackageArchiveInfo(baseApk.absolutePath, 0)?.packageName
    } catch (_: Throwable) {
      null
    }
  }

  private fun installObbFiles(packageName: String, obbFiles: List<File>) {
    if (obbFiles.isEmpty()) {
      return
    }
    @Suppress("DEPRECATION")
    val obbDir =
            File(
                    Environment.getExternalStorageDirectory(),
                    "Android/obb/$packageName",
            )
    if (!obbDir.exists()) {
      obbDir.mkdirs()
    }
    obbFiles.forEach { obbFile ->
      try {
        val target = File(obbDir, obbFile.name)
        obbFile.copyTo(target, overwrite = true)
      } catch (error: Throwable) {
        Log.w(TAG, "Failed copying OBB: ${obbFile.name}: ${error.message}")
      }
    }
  }

  private fun cleanupExtractedXapk(tempDir: File) {
    try {
      if (tempDir.exists()) {
        val deleted = tempDir.deleteRecursively()
        if (!deleted) {
          Log.w(
                  TAG,
                  "Failed to delete extracted XAPK via app IO; trying Shizuku rm: ${tempDir.absolutePath}"
          )
          val command = "rm -rf \"${tempDir.absolutePath}\""
          runShizukuCommand(command, 30)
        } else {
          Log.d(TAG, "Cleaned extracted XAPK dir: ${tempDir.absolutePath}")
        }
      }
    } catch (_: Throwable) {
      Log.w(TAG, "Failed cleaning extracted XAPK dir: ${tempDir.absolutePath}")
    }
  }

  private data class CommandResult(
          val exitCode: Int,
          val output: String,
          val errorOutput: String,
  )

  private class InstallCancelledException : Exception("Install cancelled.")

  private fun runInstallMultipleCommand(stagedPaths: List<String>): CommandResult {
    val quoted = stagedPaths.joinToString(" ") { path -> "\"$path\"" }
    val commands =
            listOf(
                    "cmd package install-multiple -r --user 0 $quoted",
                    "pm install-multiple -r --user 0 $quoted",
            )
    var lastResult: CommandResult? = null
    for (command in commands) {
      val result = runShizukuCommand(command, 120, registerProcess = true)
      lastResult = result
      if (result.exitCode == 0) {
        return result
      }
      val combined = (result.output + "\n" + result.errorOutput).lowercase()
      val unknownCommand = combined.contains("unknown command: install-multiple")
      val cmdMissing = combined.contains("cmd: not found")
      if (!unknownCommand && !cmdMissing) {
        break
      }
    }
    val combined = (lastResult?.output ?: "") + "\n" + (lastResult?.errorOutput ?: "")
    val combinedLower = combined.lowercase()
    val missingInstallMultiple =
            combinedLower.contains("unknown command: install-multiple") ||
                    combinedLower.contains("cmd: not found")
    if (missingInstallMultiple) {
      Log.w(TAG, "install-multiple unsupported, falling back to session install")
      return installUsingSession(stagedPaths)
    }
    return lastResult ?: CommandResult(-1, "", "Unable to execute install-multiple command.")
  }

  private fun installUsingSession(stagedPaths: List<String>): CommandResult {
    Log.d(TAG, "Session install start for ${stagedPaths.size} APK(s)")
    val createResult =
            runShizukuCommand(
                    "pm install-create -r --user 0",
                    30,
                    registerProcess = true,
            )
    if (createResult.exitCode != 0) {
      Log.w(
              TAG,
              "install-create failed: ${createResult.errorOutput.ifBlank { createResult.output }}"
      )
      return createResult
    }
    val sessionId = extractSessionId(createResult.output + "\n" + createResult.errorOutput)
    if (sessionId == null) {
      return CommandResult(
              -1,
              createResult.output,
              createResult.errorOutput.ifBlank { "Unable to parse install session id." },
      )
    }
    Log.d(TAG, "Session install id: $sessionId")

    val stagedFiles = stagedPaths.map { path -> File(path) }
    stagedFiles.forEachIndexed { index, file ->
      val name = file.name.ifBlank { "split_$index.apk" }
      val size = file.length()
      val writeCommand = "pm install-write -S $size $sessionId \"$name\" \"${file.absolutePath}\""
      val writeResult = runShizukuCommand(writeCommand, 60, registerProcess = true)
      if (writeResult.exitCode != 0) {
        Log.w(
                TAG,
                "install-write failed for $name: ${writeResult.errorOutput.ifBlank { writeResult.output }}",
        )
        runShizukuCommand("pm install-abandon $sessionId", 15)
        return writeResult
      }
    }

    val commitResult = runShizukuCommand("pm install-commit $sessionId", 60, registerProcess = true)
    if (commitResult.exitCode != 0) {
      Log.w(
              TAG,
              "install-commit failed: ${commitResult.errorOutput.ifBlank { commitResult.output }}",
      )
      runShizukuCommand("pm install-abandon $sessionId", 15)
    }
    Log.d(TAG, "Session install finished: exit=${commitResult.exitCode}")
    return commitResult
  }

  private fun extractSessionId(output: String): String? {
    val regex = Regex("(\\d+)")
    return regex.find(output)?.value
  }

  private fun runShizukuCommand(
          command: String,
          timeoutSeconds: Long,
          registerProcess: Boolean = false,
  ): CommandResult {
    val process =
            createShizukuProcess(arrayOf("sh", "-c", command), null, null)
                    ?: return CommandResult(-1, "", "Unable to create Shizuku process")
    if (registerProcess) {
      setActiveProcess(process)
    }
    throwIfCancelled()
    val exitCode =
            try {
              waitForExitCode(process, timeoutSeconds, "shizuku")
            } catch (error: Throwable) {
              return CommandResult(-1, "", error.message ?: "Install timed out.")
            }
    val output = readAll(process.inputStream)
    val errorOutput = readAll(process.errorStream)
    return CommandResult(exitCode, output, errorOutput)
  }

  private fun stageApkToShellTmp(apkPath: String, token: String): String {
    val sourceFile = File(apkPath)
    val targetPath = "/data/local/tmp/adb_pkg_installer_${token}.apk"
    Log.d(TAG, "stageApkToShellTmp start: $apkPath -> $targetPath")
    val process =
            createShizukuProcess(arrayOf("sh", "-c", "cat > \"$targetPath\""), null, null)
                    ?: throw IllegalStateException("Unable to create Shizuku process for staging")
    try {
      sourceFile.inputStream().use { input ->
        process.outputStream.use { output -> input.copyTo(output) }
      }
    } catch (error: Throwable) {
      process.destroy()
      throw IllegalStateException("Failed staging APK to /data/local/tmp: ${error.message}")
    }
    val exitCode = waitForExitCode(process, 30, "stageApkToShellTmp")
    if (exitCode != 0) {
      val output = readAll(process.inputStream)
      val errorOutput = readAll(process.errorStream)
      throw IllegalStateException(
              "Failed staging APK (exit=$exitCode): ${errorOutput.ifBlank { output }}",
      )
    }
    val chmodResult = runShizukuCommand("chmod 644 \"$targetPath\"", 10)
    if (chmodResult.exitCode != 0) {
      Log.w(
              TAG,
              "Failed chmod on staged APK: ${chmodResult.errorOutput.ifBlank { chmodResult.output }}"
      )
    }
    return targetPath
  }

  private fun waitForExitCode(process: Process, timeoutSeconds: Long, label: String): Int {
    Log.d(TAG, "$label wait start (timeout=${timeoutSeconds}s)")
    val latch = java.util.concurrent.CountDownLatch(1)
    val exitCodeRef = java.util.concurrent.atomic.AtomicInteger(-1)
    val errorRef = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
    val waiter = Thread {
      try {
        exitCodeRef.set(process.waitFor())
      } catch (error: Throwable) {
        errorRef.set(error)
      } finally {
        latch.countDown()
      }
    }
    waiter.start()
    throwIfCancelled()
    val finished = latch.await(timeoutSeconds, TimeUnit.SECONDS)
    throwIfCancelled()
    if (!finished) {
      Log.w(TAG, "$label timed out")
      process.destroy()
      throw IllegalStateException("Install timed out.")
    }
    errorRef.get()?.let { error ->
      Log.w(TAG, "$label waitFor failed: ${error::class.java.name}: ${error.message}")
      throw error
    }
    val exitCode = exitCodeRef.get()
    Log.d(TAG, "$label wait done: exitCode=$exitCode")
    return exitCode
  }

  private fun cleanupStagedApk(path: String) {
    try {
      val file = File(path)
      if (!file.exists()) {
        Log.d(TAG, "Staged APK already removed: $path")
        return
      }
      if (file.exists() && !file.delete()) {
        Log.w(TAG, "App delete failed for staged APK, trying Shizuku rm: $path")
        val command = "rm -f \"$path\""
        val result = runShizukuCommand(command, 15)
        if (result.exitCode != 0) {
          Log.w(
                  TAG,
                  "Shizuku rm failed for staged APK: ${result.errorOutput.ifBlank { result.output }}",
          )
        }
        val stillExists = File(path).exists()
        if (stillExists) {
          Log.w(TAG, "Staged APK still exists after Shizuku rm: $path")
        } else {
          Log.d(TAG, "Staged APK removed via Shizuku rm: $path")
        }
      } else {
        Log.d(TAG, "Staged APK removed via app delete: $path")
      }
    } catch (_: Throwable) {
      Log.w(TAG, "Failed cleaning staged APK: $path")
    }
  }

  private fun createShizukuProcess(
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

  private fun readAll(inputStream: java.io.InputStream): String {
    return try {
      BufferedReader(InputStreamReader(inputStream)).use { reader -> reader.readText() }
    } catch (_: Throwable) {
      ""
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
