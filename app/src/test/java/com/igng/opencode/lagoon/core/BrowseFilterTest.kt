package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 目录浏览器默认隐藏点开头的项，用户开启「显示隐藏目录」后才全部可见。 */
class BrowseFilterTest {
  @Test fun hiddenEntriesAreDotNames() {
    assertTrue(BrowseFilter.isHiddenEntry(".git"))
    assertTrue(BrowseFilter.isHiddenEntry("/home/me/.config"))
    assertTrue(BrowseFilter.isHiddenEntry(".wt/"))
    assertFalse(BrowseFilter.isHiddenEntry("src"))
    assertFalse(BrowseFilter.isHiddenEntry("/home/me/notes.md"))
  }

  @Test fun hiddenResultsNeedADotSegment() {
    assertTrue(BrowseFilter.isHiddenPath("/repo/.git/config"))
    assertTrue(BrowseFilter.isHiddenPath(".github/workflows"))
    assertFalse(BrowseFilter.isHiddenPath("/repo/src/main.kt"))
    assertFalse(BrowseFilter.isHiddenPath("a.b/c"))
  }

  @Test fun entriesHideDotNamesUntilAsked() {
    val nodes = listOf(FileNode(".git", "directory"), FileNode("src", "directory"), FileNode(".env", "file"))
    assertEquals(listOf("src"), BrowseFilter.entries(nodes, showHidden = false).map { it.path })
    assertEquals(listOf(".git", "src", ".env"), BrowseFilter.entries(nodes, showHidden = true).map { it.path })
  }

  @Test fun searchResultsHideDotSegmentsUntilAsked() {
    val results = listOf("/repo/src", "/repo/.github/workflows", "/repo/docs")
    assertEquals(listOf("/repo/src", "/repo/docs"), BrowseFilter.results(results, showHidden = false))
    assertEquals(results, BrowseFilter.results(results, showHidden = true))
  }
}
