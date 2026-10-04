package com.igng.opencode.lagoon.system

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Only the server-wide summary may own the Live Update / island slot. Per-session notifications
 * requesting promotion made every running session compete for the status bar chip.
 */
class SingleIslandOwnerTest {
  private fun source(path: String): String = listOf(File("app/$path"), File(path)).firstOrNull { it.isFile }?.readText()
    ?: error("找不到源文件：$path（工作目录 ${File(".").absolutePath}）")

  @Test fun onlyTheSummaryRequestsPromotionAndVendorExtras() {
    val text = source("src/main/java/com/igng/opencode/lagoon/system/TaskNotifications.kt")
    val summaryStart = text.indexOf("fun buildSummary(")
    val summaryEnd = text.indexOf("private fun summaryDismissed(")
    val summaryBody = text.substring(summaryStart, summaryEnd)
    val outside = text.removeRange(summaryStart, summaryEnd)
    assertEquals(1, Regex("setRequestPromotedOngoing").findAll(summaryBody).count())
    assertFalse(outside.contains("setRequestPromotedOngoing"))
    assertFalse(outside.contains("IslandRegistry.extendAll"))
  }

  @Test fun inAppFakeIslandIsGone() {
    assertFalse(File("app/src/main/java/com/igng/opencode/lagoon/ui/MiuixTaskIsland.kt").exists() || File("src/main/java/com/igng/opencode/lagoon/ui/MiuixTaskIsland.kt").exists())
    assertFalse(source("src/main/java/com/igng/opencode/lagoon/ui/MainActivity.kt").contains("MiuixTaskIsland"))
  }

  @Test fun xiaomiFocusTemplateIsNotWritten() {
    assertFalse(source("src/main/java/com/igng/opencode/lagoon/system/IslandAdapters.kt").contains("putString(\"miui.focus.param\""))
  }
}
