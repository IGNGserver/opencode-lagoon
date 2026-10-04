package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The dock used to slide every tab change in from the right; direction must follow tab order. */
class SlideDirectionTest {
  private fun tab(tab: RootTab) = NavRoute(tab, null, 0)

  @Test fun tabsToTheRightEnterFromTheRight() {
    assertEquals(1, slideDirection(tab(RootTab.SESSIONS), tab(RootTab.ACTIVITY)))
    assertEquals(1, slideDirection(tab(RootTab.SESSIONS), tab(RootTab.SETTINGS)))
    assertEquals(1, slideDirection(tab(RootTab.ACTIVITY), tab(RootTab.SETTINGS)))
  }

  @Test fun tabsToTheLeftEnterFromTheLeft() {
    assertEquals(-1, slideDirection(tab(RootTab.SETTINGS), tab(RootTab.SESSIONS)))
    assertEquals(-1, slideDirection(tab(RootTab.SETTINGS), tab(RootTab.ACTIVITY)))
    assertEquals(-1, slideDirection(tab(RootTab.ACTIVITY), tab(RootTab.SESSIONS)))
  }

  @Test fun openingGoesRightAndBackGoesLeftRegardlessOfTab() {
    val chat = NavRoute(RootTab.ACTIVITY, "s1", 1)
    val child = NavRoute(RootTab.ACTIVITY, "c1", 2)
    assertEquals(1, slideDirection(tab(RootTab.ACTIVITY), chat))
    assertEquals(1, slideDirection(chat, child))
    assertEquals(-1, slideDirection(child, chat))
    assertEquals(-1, slideDirection(chat, tab(RootTab.ACTIVITY)))
    // Back from a chat opened in 设置 lands on 会话 root: still a "back" (left) move.
    assertEquals(-1, slideDirection(NavRoute(RootTab.SETTINGS, "s1", 1), tab(RootTab.SESSIONS)))
  }
}
