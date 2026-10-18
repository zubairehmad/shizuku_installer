package com.zubair.shizukupackageinstaller.privileged

interface PrivilegedApi {
  fun initialize()

  fun isAvailable(): Boolean

  fun hasPermission(): Boolean

  fun getStatePayload(): Map<String, Any?>

  fun createProcess(command: String): Process

  fun close()
}
