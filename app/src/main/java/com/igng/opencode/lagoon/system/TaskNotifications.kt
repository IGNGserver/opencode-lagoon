package com.igng.opencode.lagoon.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.igng.opencode.lagoon.R
import com.igng.opencode.lagoon.core.LiveUpdateContent
import com.igng.opencode.lagoon.core.LiveUpdateStage
import com.igng.opencode.lagoon.core.ServerStore
import com.igng.opencode.lagoon.core.displayTitle
import com.igng.opencode.lagoon.core.PermissionRequest
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.TaskPhase
import com.igng.opencode.lagoon.core.TaskState
import com.igng.opencode.lagoon.core.TaskSummary
import com.igng.opencode.lagoon.ui.MainActivity

class TaskNotifications(private val context: Context) {
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val appIcon by lazy(LazyThreadSafetyMode.NONE) {
    BitmapFactory.decodeResource(context.resources, R.drawable.ic_app)
  }
  companion object {
    const val RUNNING = "task_running"
    const val ATTENTION = "task_attention"
    const val COMPLETED = "task_completed"
    const val SUMMARY = "task_summary"
    fun notificationId(serverId: String, sessionId: String): Int = "${serverId}:$sessionId".hashCode() and 0x7fffffff
    /** One server-wide island/summary notification per server. */
    fun summaryId(serverId: String): Int = "summary:$serverId".hashCode() and 0x7fffffff
  }
  init {
    manager.createNotificationChannel(NotificationChannel(RUNNING, "正在运行", NotificationManager.IMPORTANCE_DEFAULT))
    manager.createNotificationChannel(NotificationChannel(ATTENTION, "需要处理", NotificationManager.IMPORTANCE_HIGH))
    manager.createNotificationChannel(NotificationChannel(COMPLETED, "任务结果", NotificationManager.IMPORTANCE_DEFAULT))
    manager.createNotificationChannel(NotificationChannel(SUMMARY, "任务总览", NotificationManager.IMPORTANCE_DEFAULT))
  }
  private fun allowed(): Boolean = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  private fun open(serverId: String, sessionId: String): PendingIntent {
    val uri = Uri.parse("opencode-lagoon://server/${Uri.encode(serverId)}/session/${Uri.encode(sessionId)}")
    val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(context, notificationId(serverId, sessionId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }
  private fun action(name: String, profile: ServerProfile, session: Session, permission: PermissionRequest? = null): PendingIntent {
    val intent = Intent(context, NotificationActionReceiver::class.java).apply {
      this.action = name
      data = Uri.parse("opencode-lagoon://action/${Uri.encode(profile.id)}/${Uri.encode(session.id)}/$name")
      putExtra("serverId", profile.id)
      putExtra("profileUrl", profile.url)
      putExtra("sessionId", session.id)
      putExtra("directory", session.directory)
      permission?.let { putExtra("permissionId", it.id); putExtra("permissionDirectory", it.directory) }
      addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    }
    return PendingIntent.getBroadcast(context, notificationId(profile.id, session.id) xor name.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }
  fun build(profile: ServerProfile, session: Session, state: TaskState, permission: PermissionRequest? = null): Notification {
    val waiting = state.phase in TaskState.WAITING_PHASES
    val running = state.phase in TaskState.RUNNING_PHASES
    val channel = if (waiting) ATTENTION else if (running) RUNNING else COMPLETED
    val title = when (state.phase) {
      TaskPhase.COMPLETED -> "已完成 · ${session.displayTitle()}"
      TaskPhase.FAILED -> "执行失败 · ${session.displayTitle()}"
      TaskPhase.ABORTED -> "已停止 · ${session.displayTitle()}"
      TaskPhase.WAITING_PERMISSION -> "需要授权 · ${session.displayTitle()}"
      TaskPhase.WAITING_QUESTION -> "需要回答 · ${session.displayTitle()}"
      else -> "运行中 · ${session.displayTitle()}"
    }
    // When a permission is pending, show what the agent actually wants to run (action + patterns)
    // instead of the fixed "等待权限确认" so the user can decide knowingly (A16).
    val body = permission?.let(::permissionSummary) ?: state.detail
    val builder = NotificationCompat.Builder(context, channel)
      .setSmallIcon(R.drawable.ic_notification).setLargeIcon(appIcon)
      .setContentTitle(title).setContentText(body.take(200))
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setContentIntent(open(profile.id, session.id)).setAutoCancel(!state.active)
      .setOnlyAlertOnce(state.active).setOngoing(running)
      .setCategory(if (waiting) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    // Per-session notifications stay ordinary: only the server-wide summary owns the Live Update /
    // island slot, otherwise every running session would compete for the status bar chip.
    if (running) builder.addAction(0, "停止", action("abort", profile, session))
    if (state.phase == TaskPhase.WAITING_PERMISSION && permission != null && permission.action.isNotBlank() && permission.detail !in setOf("", "{}", "[]")) {
      builder.addAction(0, "拒绝", action("reject", profile, session, permission))
      builder.addAction(0, "允许一次", action("once", profile, session, permission))

    }
    if (state.phase == TaskPhase.WAITING_QUESTION) {
      builder.addAction(0, "回答", open(profile.id, session.id))
    }
    return builder.build()
  }
  private fun permissionSummary(permission: PermissionRequest): String = buildString {
    append(permission.action.ifBlank { "操作请求" })
    if (permission.detail.isNotBlank()) append("\n").append(permission.detail.take(600))
    if (permission.always.isNotEmpty()) append("\n记住规则：").append(permission.always.joinToString(", ").take(200))
  }

  /** Minimal, persistent notification for the monitoring foreground service (A09). It is separate
   *  from the per-session result notifications so removing the foreground state never removes a
   *  real task result. Its text mirrors the server-wide island summary when available. */
  fun buildMonitoring(summary: TaskSummary? = null): Notification = NotificationCompat.Builder(context, RUNNING)
    .setSmallIcon(R.drawable.ic_notification).setLargeIcon(appIcon)
    .setContentTitle("OpenCode 任务监控中")
    .setContentText(summary?.text ?: "仅跟踪当前服务器；切换后结束本地监控")
    .setOngoing(true).setShowWhen(false).setCategory(NotificationCompat.CATEGORY_SERVICE)
    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    .build()

  fun show(profile: ServerProfile, session: Session, state: TaskState, permission: PermissionRequest? = null) {
    if (!allowed()) return
    if (!profile.notifications) return
    if (state.phase == TaskPhase.IDLE || state.phase == TaskPhase.DISCONNECTED) return
    val store = ServerStore(context)
    val previous = store.taskStates(profile.id)[session.id]
    val next = store.rememberTask(profile.id, if (previous != null && previous.phase == state.phase) state.copy(since = previous.since) else state, session.parentId)
    if (next.phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED) && session.id in store.acknowledgedTasks(profile.id)) return
    val signature = permission?.id.orEmpty()
    if (!store.claimNotification(profile.id, next, signature)) return
    manager.notify(notificationId(profile.id, session.id), build(profile, session, next, permission))
  }
  fun cancel(serverId: String, sessionId: String) {
    manager.cancel(notificationId(serverId, sessionId))
  }

  /**
   * Server-wide summary. While tasks run or wait for the user it is an ongoing, promoted
   * notification (Android 16 Live Update: status bar chip / HyperOS Super Island); once everything has
   * settled it is demoted to an ordinary, dismissible notification. Look and colors are left to the
   * system; this only supplies content.
   */
  fun buildSummary(profile: ServerProfile, summary: TaskSummary, targetSessionId: String?): Notification {
    val content = LiveUpdateContent.of(summary)
    val active = content.stage == LiveUpdateStage.ACTIVE
    val builder = NotificationCompat.Builder(context, SUMMARY)
      .setSmallIcon(R.drawable.ic_notification).setLargeIcon(appIcon)
      .setContentTitle(content.title).setContentText(content.text)
      .setSubText(profile.name)
      .setStyle(NotificationCompat.BigTextStyle().bigText(content.expandedText))
      .setOngoing(active).setAutoCancel(!active)
      .setOnlyAlertOnce(true).setShowWhen(false)
      .setCategory(if (active) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setRequestPromotedOngoing(active)
      .setDeleteIntent(summaryDismissed(profile.id, content.stage))
    if (active) builder.setShortCriticalText(content.shortCriticalText)
    (targetSessionId ?: content.headlineSessionId)?.let { builder.setContentIntent(open(profile.id, it)) }
    val notification = builder.build()
    IslandRegistry.extendAll(context, profile, notification, content.title, content.expandedText, running = active, summary = summary)
    return notification
  }

  private fun summaryDismissed(serverId: String, stage: LiveUpdateStage): PendingIntent {
    val intent = Intent(context, NotificationActionReceiver::class.java).apply {
      action = NotificationActionReceiver.ACTION_SUMMARY_DISMISSED
      data = Uri.parse("opencode-lagoon://summary/${Uri.encode(serverId)}/${stage.name}")
      putExtra("serverId", serverId)
      putExtra("stage", stage.name)
    }
    return PendingIntent.getBroadcast(context, summaryId(serverId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  fun showSummary(profile: ServerProfile, summary: TaskSummary, targetSessionId: String?) {
    val stage = LiveUpdateContent.stageOf(summary)
    val dismissed = SummaryDismissals.stage(context, profile.id)
    // Any stage change ends the user's dismissal, so a new run or a final result shows again.
    if (dismissed != null && dismissed != stage) SummaryDismissals.clear(context, profile.id)
    if (!allowed() || !profile.notifications || !LiveUpdateContent.shouldPost(stage, dismissed)) {
      manager.cancel(summaryId(profile.id))
      return
    }
    manager.notify(summaryId(profile.id), buildSummary(profile, summary, targetSessionId))
  }
  fun cancelSummary(serverId: String) = manager.cancel(summaryId(serverId))

  /** 打开系统「实时更新 / 提升为常驻通知」授权页（Android 16+）。 */
  fun promotedNotificationSettingsIntent(): Intent? {
    if (Build.VERSION.SDK_INT < 36) return null
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
      .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return if (intent.resolveActivity(context.packageManager) != null) intent else null
  }
}

