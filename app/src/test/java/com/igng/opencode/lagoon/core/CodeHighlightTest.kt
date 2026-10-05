package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeHighlightTest {
  @Test fun classifiesDiffShellAndPlainLines() {
    assertEquals(CodeToken.HEADER, CodeHighlight.classify("+++ b/a.kt"))
    assertEquals(CodeToken.HEADER, CodeHighlight.classify("--- a/a.kt"))
    assertEquals(CodeToken.HEADER, CodeHighlight.classify("diff --git a/a.kt b/a.kt"))
    assertEquals(CodeToken.HUNK, CodeHighlight.classify("@@ -1,2 +1,3 @@"))
    assertEquals(CodeToken.ADD, CodeHighlight.classify("+added"))
    assertEquals(CodeToken.REMOVE, CodeHighlight.classify("-removed"))
    assertEquals(CodeToken.COMMAND, CodeHighlight.classify("$ gradle test"))
    assertEquals(CodeToken.PLAIN, CodeHighlight.classify("plain output"))
  }

  @Test fun mergesAdjacentLinesOfTheSameToken() {
    val fragments = CodeHighlight.split("+a\n+b\nplain")
    assertEquals(listOf(CodeToken.ADD, CodeToken.PLAIN), fragments.map { it.token })
    assertEquals("+a\n+b\n", fragments.first().text)
    assertEquals("plain", fragments.last().text)
  }

  @Test fun emptyTextProducesNothing() {
    assertEquals(0, CodeHighlight.split("").size)
  }
}
