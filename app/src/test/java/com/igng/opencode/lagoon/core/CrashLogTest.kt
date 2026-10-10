package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrashLogTest {
  @Test fun reportCarriesVersionDeviceThreadAndTheWholeCauseChain() {
    val error = IllegalStateException("outer", IllegalArgumentException("Key \"turn:u1:a1/\" was already used"))
    val report = CrashLog.report("main", error, "0.2.1 (12)", "Xiaomi 25010PN30C / Android 16 (API 36)", now = 0)
    assertTrue(report.contains("版本：Lagoon 0.2.1 (12)"))
    assertTrue(report.contains("设备：Xiaomi 25010PN30C"))
    assertTrue(report.contains("线程：main"))
    assertTrue(report.contains("Caused by: java.lang.IllegalArgumentException"))
    assertEquals("java.lang.IllegalStateException: outer", CrashLog.headline(report))
  }

  @Test fun reportIsBoundedSoAHugeTraceCannotFillStorage() {
    val error = RuntimeException("x".repeat(100_000))
    assertTrue(CrashLog.report("main", error, "v", "d", now = 0).length < 25_000)
  }

  @Test fun writeCreatesTheDiagnosticsDirectoryAndOverwritesThePreviousCrash() {
    val dir = Files.createTempDirectory("crash").toFile()
    val file = File(dir, "diagnostics/last-crash.txt")
    CrashLog.write(file, "first")
    CrashLog.write(file, "second")
    assertEquals("second", file.readText())
    dir.deleteRecursively()
  }
}
