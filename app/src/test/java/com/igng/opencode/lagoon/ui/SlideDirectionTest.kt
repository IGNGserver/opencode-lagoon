package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Deeper pages enter from the right and going back enters from the left, whatever the entry point. */
class SlideDirectionTest {
  private val home = SessionNavigation().route

  @Test fun openingAPageOrChatEntersFromTheRight() {
    assertEquals(1, slideDirection(home, SessionNavigation().push(RootPage.SETTINGS).route))
    assertEquals(1, slideDirection(home, SessionNavigation().open("s1").route))
    assertEquals(1, slideDirection(SessionNavigation().open("s1").route, SessionNavigation().open("s1").open("c1", true).route))
  }

  @Test fun goingBackEntersFromTheLeft() {
    val child = SessionNavigation(RootPage.ARCHIVED).open("s1").open("c1", true)
    assertEquals(-1, slideDirection(child.route, child.back().route))
    assertEquals(-1, slideDirection(child.back().route, child.back().back().route))
    assertEquals(-1, slideDirection(SessionNavigation().push(RootPage.SETTINGS).route, home))
  }

  @Test fun replacingAChatAtTheSameDepthEntersFromTheRight() {
    assertEquals(1, slideDirection(SessionNavigation().open("a").route, SessionNavigation().open("b").route))
  }
}
