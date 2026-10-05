package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshModeTest {
  @Test fun fastIsQuickerThanStandard() {
    for (mode in RefreshMode.entries) {
      assertTrue(mode.activeReconcileMs > 0)
      assertTrue(mode.idleReconcileMs >= mode.activeReconcileMs)
      assertTrue(mode.foregroundRefreshMs > 0)
    }
    assertTrue(RefreshMode.FAST.activeReconcileMs < RefreshMode.STANDARD.activeReconcileMs)
    assertTrue(RefreshMode.FAST.idleReconcileMs < RefreshMode.STANDARD.idleReconcileMs)
    assertTrue(RefreshMode.FAST.foregroundRefreshMs < RefreshMode.STANDARD.foregroundRefreshMs)
  }

  @Test fun storeRemembersTheChosenMode() {
    // 缺省即为「更快」，避免旧用户升级后仍觉得刷新慢。
    assertEquals(RefreshMode.FAST, RefreshMode.entries.first { it.label == "更快" })
  }
}
