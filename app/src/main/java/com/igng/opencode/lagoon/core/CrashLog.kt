package com.igng.opencode.lagoon.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last uncaught crash on the device so it can be copied from 设置 › 关于与诊断 without adb.
 *
 * Only the exception chain, app version and device model are written — never credentials, messages
 * or file content — and the file stays in app-private storage until the user clears it. The platform
 * handler still runs afterwards, so crash behaviour itself is unchanged.
 */
object CrashLog {
  private const val MAX_CHARS = 24_000
  @Volatile private var installed = false

  fun install(context: Context) {
    if (installed) return
    installed = true
    val app = context.applicationContext
    val version = runCatching {
      val info = app.packageManager.getPackageInfo(app.packageName, 0)
      val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
      "${info.versionName} ($code)"
    }.getOrDefault("unknown")
    val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      runCatching { write(file(app), report(thread.name, error, version, device)) }
      previous?.uncaughtException(thread, error)
    }
  }

  fun read(context: Context): String? = file(context).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

  fun clear(context: Context) { file(context).delete() }

  internal fun report(thread: String, error: Throwable, version: String, device: String, now: Long = System.currentTimeMillis()): String {
    val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    return buildString {
      append("时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(now))).append('\n')
      append("版本：Lagoon ").append(version).append('\n')
      append("设备：").append(device).append('\n')
      append("线程：").append(thread).append("\n\n")
      append(trace.take(MAX_CHARS))
    }
  }

  /** First line of the stored report's exception, for a one-line summary in settings. */
  internal fun headline(report: String): String = report.substringAfter("\n\n", report).lineSequence().firstOrNull().orEmpty().take(160)

  internal fun write(file: File, text: String) {
    file.parentFile?.mkdirs()
    file.writeText(text)
  }

  private fun file(context: Context) = File(context.filesDir, "diagnostics/last-crash.txt")
}
