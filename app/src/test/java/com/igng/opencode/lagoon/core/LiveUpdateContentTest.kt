package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateContentTest {
  private fun summary(vararg tasks: Triple<String, TaskPhase, Long>): TaskSummary = TaskSummary.of(
    tasks.associate { (id, phase, since) -> id to TaskState(id, phase, since = since) },
    titles = tasks.associate { (id, _, _) -> id to "会话$id" }
  )

  @Test fun runningOrWaitingIsActive() {
    assertEquals(LiveUpdateStage.ACTIVE, LiveUpdateContent.stageOf(summary(Triple("a", TaskPhase.TOOL, 1))))
    assertEquals(LiveUpdateStage.ACTIVE, LiveUpdateContent.stageOf(summary(Triple("a", TaskPhase.WAITING_PERMISSION, 1))))
  }

  @Test fun onlyTerminalResultsAreSettledNotActive() {
    // A lingering "0跑·1完" must not keep the Live Update / island alive.
    val settled = summary(Triple("a", TaskPhase.COMPLETED, 1), Triple("b", TaskPhase.FAILED, 2))
    assertEquals(LiveUpdateStage.SETTLED, LiveUpdateContent.stageOf(settled))
    assertEquals("1 个已完成，1 个失败", LiveUpdateContent.of(settled).title)
  }

  @Test fun emptySummaryIsEmptyStage() {
    assertEquals(LiveUpdateStage.EMPTY, LiveUpdateContent.stageOf(TaskSummary.EMPTY))
    assertNull(LiveUpdateContent.of(TaskSummary.EMPTY).headlineSessionId)
  }

  @Test fun titlePrioritisesWaitingThenRunning() {
    val content = LiveUpdateContent.of(summary(Triple("a", TaskPhase.THINKING, 1), Triple("b", TaskPhase.WAITING_QUESTION, 2)))
    assertEquals("1 个任务待你处理", content.title)
    assertEquals("b", content.headlineSessionId)
    assertEquals("会话b", content.text)
    assertEquals("2 个任务运行中", LiveUpdateContent.of(summary(Triple("a", TaskPhase.THINKING, 1), Triple("b", TaskPhase.TOOL, 2))).title)
  }

  @Test fun runningOnlySummaryStillHasATapTarget() {
    assertEquals("a", LiveUpdateContent.of(summary(Triple("a", TaskPhase.SUBAGENT, 1))).headlineSessionId)
  }

  @Test fun expandedTextUsesPlainLabelsAndCountsHiddenTasks() {
    val content = LiveUpdateContent.of(summary(
      Triple("a", TaskPhase.THINKING, 4), Triple("b", TaskPhase.TOOL, 3),
      Triple("c", TaskPhase.COMPLETED, 2), Triple("d", TaskPhase.COMPLETED, 1)
    ))
    assertEquals("运行中 · 会话a\n运行中 · 会话b\n已完成 · 会话c\n另有 1 个任务", content.expandedText)
    assertFalse(content.expandedText.contains("●"))
    assertFalse(content.expandedText.contains("["))
  }

  @Test fun phaseLabelsNeverLeakEnumNames() {
    TaskPhase.entries.forEach { assertFalse(LiveUpdateContent.phaseLabel(it).any { ch -> ch in 'A'..'Z' }) }
  }

  @Test fun shortCriticalTextFitsTheStatusChip() {
    // Android shows the whole chip text only below 7 characters.
    assertTrue(LiveUpdateContent.of(summary(Triple("a", TaskPhase.THINKING, 1), Triple("b", TaskPhase.COMPLETED, 2))).shortCriticalText.length < 7)
  }

  @Test fun dismissalHoldsOnlyWhileTheStageIsUnchanged() {
    assertFalse(LiveUpdateContent.shouldPost(LiveUpdateStage.SETTLED, LiveUpdateStage.SETTLED))
    assertTrue(LiveUpdateContent.shouldPost(LiveUpdateStage.ACTIVE, LiveUpdateStage.SETTLED))
    assertTrue(LiveUpdateContent.shouldPost(LiveUpdateStage.SETTLED, LiveUpdateStage.ACTIVE))
    assertTrue(LiveUpdateContent.shouldPost(LiveUpdateStage.ACTIVE, null))
    assertFalse(LiveUpdateContent.shouldPost(LiveUpdateStage.EMPTY, null))
  }
}
