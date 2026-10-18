package com.zubair.shizukupackageinstaller

interface PrivilegedApi {
  fun isAvailable(): Boolean

  fun hasPermission(): Boolean

  fun createProcess(command: Array<String>, env: Array<String>?, dir: String?): Process?
}
