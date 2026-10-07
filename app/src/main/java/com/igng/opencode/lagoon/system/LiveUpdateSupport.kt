package com.igng.opencode.lagoon.system

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 系统「实时更新」（Android 16 Live Updates）在本机的可用状态，用于设置页展示与诊断。
 *
 * 本应用只使用标准通道：把服务器总览通知提升为常驻实时更新，外观完全由系统决定
 * （状态栏胶囊、锁屏 / 息屏卡片；厂商系统把标准实时更新映射到自家岛或胶囊也由系统完成）。
 * 不写入任何厂商私有 extras，也不申请任何厂商通道。
 */
data class LiveUpdateSupport(
  val supported: Boolean,
  val granted: Boolean,
  val note: String
) {
  companion object {
    fun detect(context: Context): LiveUpdateSupport {
      val supported = Build.VERSION.SDK_INT >= 36
      val manager = context.getSystemService(NotificationManager::class.java)
      val granted = supported && runCatching { manager?.canPostPromotedNotifications() == true }.getOrDefault(false)
      return LiveUpdateSupport(
        supported = supported,
        granted = granted,
        note = when {
          !supported -> "需要 Android 16 及以上；当前系统仍会照常显示普通任务通知。"
          granted -> "任务运行或等待处理时，总览会以系统样式显示在状态栏胶囊 / 锁屏实时卡片；全部结束后变为普通通知。"
          else -> "系统尚未允许实时更新。请在系统设置中允许本应用「实时更新 / 提升为常驻通知」。"
        }
      )
    }
  }
}
