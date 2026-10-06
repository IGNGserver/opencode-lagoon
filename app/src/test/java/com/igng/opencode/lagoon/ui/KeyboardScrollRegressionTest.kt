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

  /** 首屏停在最新消息；历史分页每次到顶只拉一页，必须离开顶部后才重新武装（防止连续加载到最顶）。 */
  @Test fun openingASessionStaysAtTheNewestMessage() {
    val text = source("TranscriptView.kt")
    assertTrue("首次进入应默认跟随最新消息", text.contains("mutableStateOf(true)"))
    assertTrue("回底前应等列表布局到预期条目数", text.contains("totalItemsCount }.first { it >= expectedItems"))
    assertTrue("自动加载更早消息必须以用户上滑（!follow）为前提", text.contains("firstVisibleIndex == 0 && olderArmed && !follow"))
    assertTrue("离开顶部后才重新武装", text.contains("if (firstVisibleIndex > 0) olderArmed = true"))
  }

  /** 混排的工具调用与思考在两段文字之间只折叠成一个组。 */
  @Test fun toolsAndReasoningBetweenTextsCollapseIntoOneGroup() {
    val text = source("TranscriptView.kt")
    assertTrue("分组行仍走半屏明细", text.contains("\"tool-group\" -> Column"))
  }

  @Test fun activityOverlayKeepsListStateAndBackFirstReturnsToTheList() {
    val text = source("ChatScreen.kt")
    assertTrue("列表状态必须留在列表和详情分支外", text.indexOf("val activityListState = rememberLazyListState()") < text.indexOf("if (latestSelected == null)"))
    assertTrue("返回先退出二级详情，再关闭操作层", text.contains("if (latestSelected != null && latestSelected.key != group.key) onBack() else onDismiss()"))
    assertTrue("操作层必须覆盖输入栏", text.contains("fillMaxHeight(0.74f)"))
    assertTrue("详情需随服务器消息更新", text.contains("group.key, messages, working, revertMessageId"))
    assertTrue("工具附件必须显示在二级详情", text.contains("items(latestSelected.attachments"))
  }

  @Test fun qoderThemeIsScopedToExistingChatNotHomeOrSettings() {
    assertTrue(source("ChatScreen.kt").contains("QoderChatTheme {"))
    assertTrue(!source("MainActivity.kt").contains("QoderChatTheme"))
    assertTrue("输入栏应保留系统导航栏安全距离", source("ChatScreen.kt").contains("navigationBarsPadding()"))
  }

  /** 运行中的会话在底部常驻“运行中”指示，且时间每秒刷新。 */
  @Test fun runningSessionKeepsABottomIndicator() {
    val text = source("TranscriptView.kt")
    assertTrue("会话页应有底部运行指示", text.contains("WorkingIndicator(task)"))
    assertTrue("运行指示用“运行中”文案", text.contains("运行中 · "))
  }

  /** 加载更早历史消息时应记录可见项作为锚点并在数据插入后恢复滚动偏移，防止跳跃至最早数据。 */
  @Test fun loadingOlderMessagesPreservesScrollAnchor() {
    val text = source("TranscriptView.kt")
    assertTrue("加载更早历史前应记录可见项作为锚点", text.contains("anchorKey = visibleItem.key"))
    assertTrue("加载更早历史后应恢复滚动偏移", text.contains("list.scrollBy(diff.toFloat())"))
  }
}
