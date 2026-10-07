package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateProgressTest {
  @Test fun segmentsPreserveStatusOrderAndNormalizeToOneRail() {
    val progress = LiveUpdateProgress.of(TaskSummary(running = 2, waiting = 1, completed = 1, failed = 0))

    assertEquals(
      listOf(LiveUpdateBucket.RUNNING, LiveUpdateBucket.WAITING, LiveUpdateBucket.COMPLETED),
      progress.segments.map { it.bucket }
    )
    assertEquals(listOf(50, 25, 25), progress.segments.map { it.length })
    assertEquals(100, progress.maxProgress)
    assertEquals(LiveUpdateBucket.WAITING, progress.focus)
    assertTrue(progress.position in 51..74)
  }

  @Test fun failedAndCompletedResultsAreIncludedWithoutKeepingIslandActive() {
    val summary = TaskSummary(running = 1, completed = 3, failed = 1)
    val progress = LiveUpdateProgress.of(summary)

    assertEquals(listOf(1, 3, 1), progress.segments.map { it.count })
    assertEquals(LiveUpdateBucket.RUNNING, progress.focus)
    assertEquals(LiveUpdateStage.ACTIVE, LiveUpdateContent.stageOf(summary))
  }

  @Test fun emptySummaryHasNoSegments() {
    val progress = LiveUpdateProgress.of(TaskSummary.EMPTY)

    assertEquals(emptyList<LiveUpdateSegment>(), progress.segments)
    assertEquals(0, progress.position)
    assertEquals(null, progress.focus)
  }

  @Test fun hugeCountsStayWithinPlatformRailLength() {
    val progress = LiveUpdateProgress.of(TaskSummary(running = Int.MAX_VALUE, failed = 1))

    assertEquals(100, progress.maxProgress)
    assertEquals(100, progress.segments.sumOf { it.length })
    assertTrue(progress.segments.all { it.length > 0 })
  }
}
