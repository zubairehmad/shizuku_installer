package com.zubair.shizukupackageinstaller.privileged

import android.content.Context
import com.zubair.shizukupackageinstaller.privileged.porter.PorterApi
import com.zubair.shizukupackageinstaller.privileged.shizuku.ShizukuApi

class ProviderSelector(context: Context) {
  private val porterApi = PorterApi(context)
  private val shizukuApi = ShizukuApi(context)

  private var selectedProvider: PrivilegedApi? = null
  val privilegedApi: PrivilegedApi?
    get() = selectedProvider

  fun initialize() {
    porterApi.initialize()
    shizukuApi.initialize()
  }

  fun getStatePayload(): Map<String, Any?> {
    val porterState = porterApi.getStatePayload()
    val shizukuState = shizukuApi.getStatePayload()

    if (porterState["status"] == "ready") {
      selectedProvider = porterApi
      return withSelectedProvider(porterState, "porter")
    } else if (shizukuState["status"] == "ready") {
      selectedProvider = shizukuApi
      return withSelectedProvider(shizukuState, "shizuku")
    }

    selectedProvider = null
    return fallbackState(porterState, shizukuState)
  }

  fun close() {
    selectedProvider = null
    porterApi.close()
    shizukuApi.close()
  }

  private fun withSelectedProvider(
          state: Map<String, Any?>,
          provider: String,
  ): Map<String, Any?> {
    return state + ("selectedProvider" to provider)
  }

  private fun fallbackState(
          porterState: Map<String, Any?>,
          shizukuState: Map<String, Any?>,
  ): Map<String, Any?> {
    val porterInstalled = porterState["installed"] == true
    val shizukuInstalled = shizukuState["installed"] == true

    if (!porterInstalled && !shizukuInstalled) {
      return aggregate(
              "providers_not_installed",
              "Neither Porter nor Shizuku is installed.",
              installed = false,
              running = false,
              permissionGranted = false,
      )
    }

    if (porterInstalled && !shizukuInstalled) {
      return porterState + ("selectedProvider" to null)
    } else if (!porterInstalled && shizukuInstalled) {
      return shizukuState + ("selectedProvider" to null)
    }

    val porterRunning = porterState["running"] == true
    val shizukuRunning = shizukuState["running"] == true
    val porterPermission = porterState["permissionGranted"] == true
    val shizukuPermission = shizukuState["permissionGranted"] == true

    if (!porterRunning && !shizukuRunning) {
      return aggregate(
              "providers_not_running",
              "Both Porter and Shizuku are installed but neither is running.",
              installed = true,
              running = false,
              permissionGranted = false,
      )
    } else if (porterRunning && shizukuRunning && !porterPermission && !shizukuPermission) {
      return aggregate(
              "providers_permission_required",
              "Both Porter and Shizuku are running, but this app lacks permission to use either.",
              installed = true,
              running = true,
              permissionGranted = false,
      )
    }

    val unavailable =
            listOf(
                            "Porter" to porterState,
                            "Shizuku" to shizukuState,
                    )
                    .filter { (_, state) -> state["status"] != "ready" }
                    .joinToString("\n") { (name, state) ->
                      "$name: ${state["message"] ?: "unavailable"}"
                    }
    return aggregate(
            "providers_unavailable",
            "Neither Porter nor Shizuku is ready.\n$unavailable",
            installed = true,
            running = porterRunning || shizukuRunning,
            permissionGranted = porterPermission || shizukuPermission,
    )
  }

  private fun aggregate(
          status: String,
          message: String,
          installed: Boolean,
          running: Boolean,
          permissionGranted: Boolean,
  ): Map<String, Any?> {
    return mapOf(
            "status" to status,
            "message" to message,
            "installed" to installed,
            "running" to running,
            "permissionGranted" to permissionGranted,
            "selectedProvider" to null,
    )
  }
}
