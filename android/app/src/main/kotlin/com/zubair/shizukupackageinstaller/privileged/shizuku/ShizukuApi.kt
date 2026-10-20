package com.zubair.shizukupackageinstaller.privileged.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.zubair.shizukupackageinstaller.privileged.PrivilegedApi
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class ShizukuApi(private val context: Context) : PrivilegedApi {
  companion object {
    private const val TAG = "ShizukuInstaller"
    private val SHIZUKU_MANAGER_PACKAGES =
            arrayOf(
                    "moe.shizuku.privileged.api",
                    "moe.shizuku.manager",
            )
  }

  @Volatile private var binderAvailable = false

  override fun getName(): String = "Shizuku"

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

  override fun initialize() {
    requestBinderIfNeeded()
    binderAvailable = isBinderAlive()
    Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
    Shizuku.addBinderDeadListener(binderDeadListener)
  }

  override fun close() {
    Shizuku.removeBinderReceivedListener(binderReceivedListener)
    Shizuku.removeBinderDeadListener(binderDeadListener)
  }

  override fun isAvailable(): Boolean {
    return binderAvailable || isBinderAlive()
  }

  override fun hasPermission(): Boolean {
    return try {
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
      false
    }
  }

  override fun createProcess(command: String): Process {
    return try {
      val method =
              Shizuku::class.java.getDeclaredMethod(
                      "newProcess",
                      Array<String>::class.java,
                      Array<String>::class.java,
                      String::class.java,
              )
      method.isAccessible = true
      method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
              ?: throw IllegalStateException("Unable to create Shizuku process")
    } catch (error: Throwable) {
      Log.w(TAG, "Failed to create Shizuku process: ${error.message}")
      throw IllegalStateException("Unable to create Shizuku process", error)
    }
  }

  override fun getStatePayload(): Map<String, Any?> {
    requestBinderIfNeeded()
    val running = isAvailable()
    Log.d(TAG, "Shizuku state check: running=$running, binderAvailable=$binderAvailable")
    if (running) {
      binderAvailable = true
      return if (hasPermission()) {
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

    val installed = SHIZUKU_MANAGER_PACKAGES.any { isPackageInstalled(it) }
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

  private fun isBinderAlive(): Boolean {
    return try {
      val ping = Shizuku.pingBinder()
      val binderAlive = Shizuku.getBinder()?.isBinderAlive == true
      ping || binderAlive
    } catch (_: Throwable) {
      false
    }
  }

  private fun requestBinderIfNeeded() {
    if (binderAvailable) {
      return
    }

    try {
      ShizukuProvider.requestBinderForNonProviderProcess(context)
      Log.d(TAG, "Requested Shizuku binder from provider")
    } catch (error: Throwable) {
      Log.w(TAG, "Failed requesting Shizuku binder: ${error.message}")
    }
  }

  private fun isPackageInstalled(packageName: String): Boolean {
    return try {
      context.packageManager.getPackageInfo(packageName, 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    } catch (_: Throwable) {
      false
    }
  }
}
