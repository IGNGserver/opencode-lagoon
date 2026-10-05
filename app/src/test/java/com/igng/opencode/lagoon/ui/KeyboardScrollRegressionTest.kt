package com.igng.opencode.lagoon.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话页在键盘弹出时必须自动回到底部（曾经必须手动下滑，最后几条被键盘挡住）。
 * JVM 单测起不了 Compose 组合，沿用源码扫描守住这条回归线。
 */
class KeyboardScrollRegressionTest {
  private fun source(name: String): String = listOf(
    File("app/src/main/java/com/igng/opencode/lagoon/ui/$name"),
    File("src/main/java/com/igng/opencode/lagoon/ui/$name")
  ).firstOrNull { it.isFile }?.readText() ?: error("找不到 UI 源文件：$name")

  @Test fun conversationScrollsToBottomWhenTheImeOpens() {
    val text = source("TranscriptView.kt")
    assertTrue("会话页应读取 IME 高度", text.contains("WindowInsets.ime.getBottom"))
    assertTrue("键盘打开时应触发回到底部", text.contains("LaunchedEffect(imeBottom > 0)"))
    assertTrue("键盘打开时应调用 scrollBottom()", text.contains("scrollBottom()"))
  }

  /** 首屏停在最新消息，只有用户主动上滑才加载更早历史（曾经首屏 atTop 会一路加载到最顶）。 */
  @Test fun openingASessionStaysAtTheNewestMessage() {
    val text = source("TranscriptView.kt")
    assertTrue("首次进入应默认跟随最新消息", text.contains("mutableStateOf(true)"))
    assertTrue("回底前应等列表布局到预期条目数", text.contains("totalItemsCount }.first { it >= expectedItems"))
    assertTrue("自动加载更早消息必须以用户上滑（!follow）为前提", text.contains("atTop && !follow"))
  }

  /** 运行中的会话在底部常驻“运行中”指示，且时间每秒刷新。 */
  @Test fun runningSessionKeepsABottomIndicator() {
    val text = source("TranscriptView.kt")
    assertTrue("会话页应有底部运行指示", text.contains("WorkingIndicator(task)"))
    assertTrue("运行指示用“运行中”文案", text.contains("运行中 · "))
  }
}
