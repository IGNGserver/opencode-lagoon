package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSummaryTest {
  private fun tasks(vararg phases: Pair<String, TaskPhase>): Map<String, TaskState> =
    phases.associate { (id, phase) -> id to TaskState(id, phase) }

  @Test fun emptySummaryHidesIsland() {
    assertTrue(TaskSummary.of(emptyMap()).isEmpty)
    assertNull(TaskSummary.of(emptyMap()).text)
    assertTrue(TaskSummary.of(tasks("a" to TaskPhase.IDLE, "b" to TaskPhase.DISCONNECTED)).isEmpty)
  }

  @Test fun alwaysShowsRunningAndCompletedBaseline() {
    val summary = TaskSummary.of(tasks(
      "a" to TaskPhase.THINKING,
      "b" to TaskPhase.TOOL,
      "c" to TaskPhase.COMPLETED
    ))
    assertEquals(2, summary.running)
    assertEquals(1, summary.completed)
    assertEquals(0, summary.waiting)
    assertEquals("2个运行中，1个已完成", summary.text)
  }

  @Test fun zeroCountsStillRenderBaselineWithoutWaitingOrFailed() {
    val summary = TaskSummary.of(tasks("a" to TaskPhase.COMPLETED))
    assertEquals("0个运行中，1个已完成", summary.text)
    assertFalse(summary.text!!.contains("待回复"))
    assertFalse(summary.text!!.contains("失败"))
  }

  @Test fun permissionAndQuestionBothCountAsWaiting() {
    val summary = TaskSummary.of(tasks(
      "a" to TaskPhase.WAITING_PERMISSION,
      "b" to TaskPhase.WAITING_QUESTION,
      "c" to TaskPhase.SUBAGENT,
      "d" to TaskPhase.TESTING
    ))
    assertEquals(2, summary.running)
    assertEquals(2, summary.waiting)
    assertEquals("2个运行中，0个已完成，2个待回复", summary.text)
  }

  @Test fun failedIsReportedSeparatelyFromCompleted() {
    val summary = TaskSummary.of(tasks(
      "a" to TaskPhase.FAILED,
      "b" to TaskPhase.COMPLETED
    ))
    assertEquals(0, summary.running)
    assertEquals(1, summary.completed)
    assertEquals(0, summary.waiting)
    assertEquals(1, summary.failed)
    assertEquals("0个运行中，1个已完成，1个失败", summary.text)
  }

  @Test fun acknowledgedTerminalResultsAreNotUnread() {
    val all = tasks("done" to TaskPhase.COMPLETED, "boom" to TaskPhase.FAILED)
    val summary = TaskSummary.of(all, acknowledged = setOf("done", "boom"))
    assertTrue(summary.isEmpty)
    assertNull(summary.text)
  }

  @Test fun acknowledgementIsPerSession() {
    val summary = TaskSummary.of(
      tasks("done" to TaskPhase.COMPLETED, "other" to TaskPhase.FAILED),
      acknowledged = setOf("done")
    )
    assertEquals(0, summary.completed)
    assertEquals(1, summary.failed)
    assertEquals("0个运行中，0个已完成，1个失败", summary.text)
  }

  @Test fun abortedIsNeitherCompletedNorFailed() {
    val summary = TaskSummary.of(tasks("a" to TaskPhase.ABORTED))
    assertTrue(summary.isEmpty)
  }

  @Test fun shortTextForStatusChipIncludesCompleted() {
    val summary = TaskSummary.of(tasks(
      "a" to TaskPhase.THINKING,
      "b" to TaskPhase.WAITING_QUESTION,
      "c" to TaskPhase.FAILED
    ))
    assertEquals("1跑·0完·1待·1败", summary.shortText)

    val completedOnly = TaskSummary.of(tasks("a" to TaskPhase.COMPLETED))
    assertEquals("0跑·1完", completedOnly.shortText)

    val runningAndCompleted = TaskSummary.of(tasks(
      "a" to TaskPhase.THINKING,
      "b" to TaskPhase.COMPLETED,
      "c" to TaskPhase.COMPLETED
    ))
    assertEquals("1跑·2完", runningAndCompleted.shortText)
  }

  @Test fun fullSentenceOrderIsRunningCompletedWaitingFailed() {
    val summary = TaskSummary.of(tasks(
      "a" to TaskPhase.THINKING,
      "b" to TaskPhase.WAITING_PERMISSION,
      "c" to TaskPhase.COMPLETED,
      "d" to TaskPhase.FAILED
    ))
    assertEquals("1个运行中，1个已完成，1个待回复，1个失败", summary.text)
  }

  @Test fun takesAtMostThreeItemsWithPriorityOrder() {
    val tasksMap = mapOf(
      "comp1" to TaskState("comp1", TaskPhase.COMPLETED, since = 100),
      "comp2" to TaskState("comp2", TaskPhase.COMPLETED, since = 200),
      "run1" to TaskState("run1", TaskPhase.THINKING, since = 300),
      "run2" to TaskState("run2", TaskPhase.TOOL, since = 400),
      "wait1" to TaskState("wait1", TaskPhase.WAITING_QUESTION, since = 500),
      "fail1" to TaskState("fail1", TaskPhase.FAILED, since = 600)
    )
    val titles = mapOf(
      "comp1" to "完成任务1",
      "comp2" to "完成任务2",
      "run1" to "运行任务1",
      "run2" to "运行任务2",
      "wait1" to "等待任务1",
      "fail1" to "失败任务1"
    )
    val summary = TaskSummary.of(tasksMap, titles = titles)
    assertEquals(3, summary.items.size)
    // Priority order: wait(5) > run(4) > fail(3) > comp(2)
    // Top 3 should be wait1, run2, run1
    assertEquals("wait1", summary.items[0].sessionId)
    assertEquals("等待任务1", summary.items[0].title)
    assertEquals("run2", summary.items[1].sessionId)
    assertEquals("run1", summary.items[2].sessionId)
  }
}
