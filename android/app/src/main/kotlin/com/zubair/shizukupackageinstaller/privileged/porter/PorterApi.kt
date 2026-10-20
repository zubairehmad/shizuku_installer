package com.zubair.shizukupackageinstaller.privileged.porter

import android.content.Context
import android.util.Log
import com.zubair.shizukupackageinstaller.privileged.PrivilegedApi
import eu.darken.porter.sdk.PermissionState
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterAvailability
import eu.darken.porter.sdk.PorterConnection
import eu.darken.porter.sdk.PorterConnectionState
import eu.darken.porter.sdk.extras.startProcess
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PorterApi(private val context: Context) : PrivilegedApi {
  companion object {
    private const val TAG = "PorterInstaller"
  }

  private val connection = AtomicReference<PorterConnection?>(null)
  @Volatile private var scope: CoroutineScope? = null

  override fun initialize() {
    if (scope != null) {
      return
    }

    val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope = newScope
    newScope.launch {
      Porter.connection.collect { connection.set(it) }
    }
  }

  override fun isAvailable(): Boolean {
    val currentConnection = connection.get() ?: return false
    return try {
      runBlocking { currentConnection.isAlive() }
    } catch (error: Throwable) {
      Log.w(TAG, "Porter connection is unavailable: ${error.message}")
      connection.compareAndSet(currentConnection, null)
      false
    }
  }

  override fun hasPermission(): Boolean {
    val currentConnection = connection.get() ?: return false
    return try {
      currentConnection.permission.value is PermissionState.Granted
    } catch (error: Throwable) {
      Log.w(TAG, "Unable to check Porter permission: ${error.message}")
      if (Porter.connection.value !== currentConnection) {
        connection.compareAndSet(currentConnection, null)
      }
      false
    }
  }

  override fun getStatePayload(): Map<String, Any?> {
    val state = Porter.state.value
    return when (state) {
      is PorterConnectionState.Connected -> {
        val currentConnection = state.connection
        connection.set(currentConnection)
        if (hasPermission()) {
          mapOf(
                  "status" to "ready",
                  "message" to "Porter is ready.",
                  "installed" to true,
                  "running" to true,
                  "permissionGranted" to true,
          )
        } else {
          mapOf(
                  "status" to "running_no_permission",
                  "message" to "Porter is running but permission is not granted.",
                  "installed" to true,
                  "running" to true,
                  "permissionGranted" to false,
          )
        }
      }
      is PorterConnectionState.Incompatible -> {
        mapOf(
                "status" to "porter_incompatible",
                "message" to "Porter is running but its protocol is incompatible with this app. Please try installing a version of Porter compatible with Porter SDK 0.9.0.",
                "installed" to true,
                "running" to true,
                "permissionGranted" to false,
        )
      }
      PorterConnectionState.Disconnected -> stateForDisconnected()
    }
  }

  override fun createProcess(command: String): Process {
    val currentConnection =
            connection.get()?.takeIf { isConnectionCurrent(it) }
                    ?: throw IllegalStateException("Porter is not connected")
    if (!hasPermission()) {
      throw SecurityException("Porter permission not granted")
    }

    return try {
      runBlocking {
        currentConnection.startProcess("sh", "-c", command)
      }
    } catch (error: Throwable) {
      if (Porter.connection.value !== currentConnection) {
        connection.compareAndSet(currentConnection, null)
      }
      Log.w(TAG, "Failed to create Porter process: ${error.message}")
      throw IllegalStateException("Unable to create Porter process", error)
    }
  }

  override fun close() {
    scope?.cancel()
    scope = null
    connection.set(null)
  }

  private fun isConnectionCurrent(candidate: PorterConnection): Boolean {
    return Porter.connection.value === candidate && isAvailable()
  }

  private fun stateForDisconnected(): Map<String, Any?> {
    return try {
      when (val availability = runBlocking { Porter.availability(context) }) {
        PorterAvailability.NotInstalled ->
          mapOf(
                  "status" to "manager_not_installed",
                  "message" to "Porter manager is not installed.",
                  "installed" to false,
                  "running" to false,
                  "permissionGranted" to false,
          )
        is PorterAvailability.InstalledNotConnected ->
          mapOf(
                  "status" to "installed_not_running",
                  "message" to "Porter is installed but not running.",
                  "installed" to true,
                  "running" to false,
                  "permissionGranted" to false,
          )
        is PorterAvailability.InstalledUnrecognized ->
          mapOf(
                  "status" to "porter_installed_unrecognized",
                  "message" to "An unrecognized manager app was detected. Please install the official Porter manager/application.",
                  "installed" to true,
                  "running" to false,
                  "permissionGranted" to false,
          )
        is PorterAvailability.Incompatible ->
          mapOf(
                  "status" to "porter_incompatible",
                  "message" to "Porter is running but its protocol is incompatible with this app.",
                  "installed" to true,
                  "running" to true,
                  "permissionGranted" to false,
          )
        is PorterAvailability.Connected ->
          connectedStatePayload(connection.get())
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Unable to determine Porter availability: ${error.message}")
      mapOf(
              "status" to "porter_state_unavailable",
              "message" to "Unable to determine Porter state.",
              "installed" to false,
              "running" to false,
              "permissionGranted" to false,
      )
    }
  }

  private fun connectedStatePayload(currentConnection: PorterConnection?): Map<String, Any?> {
    if (currentConnection == null) {
      return mapOf(
              "status" to "porter_state_unavailable",
              "message" to "Porter reported a connection, but it is no longer available.",
              "installed" to false,
              "running" to false,
              "permissionGranted" to false,
      )
    }
    connection.set(currentConnection)
    return if (hasPermission()) {
      mapOf(
              "status" to "ready",
              "message" to "Porter is ready.",
              "installed" to true,
              "running" to true,
              "permissionGranted" to true,
      )
    } else {
      mapOf(
              "status" to "running_no_permission",
              "message" to "Porter is running but permission is not granted.",
              "installed" to true,
              "running" to true,
              "permissionGranted" to false,
      )
    }
  }
}
