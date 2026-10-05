package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionExperienceTest {
  @Test fun worktreeUsesAuthoritativeProjectIdAndKeepsItsExecutionDirectory() {
    val session = JSONObject("""{"id":"s","title":"","projectID":"p","location":{"directory":"/trees/task"},"time":{"updated":5}}""").toSession()
    val project = Project("p", "/project", "Project")
    assertEquals(project, resolveSessionProject(session, listOf(project, Project("other", "/trees", "Wrong"))))
    assertEquals("/trees/task", session.directory)
    // 官方口径：标题只看 session.title，空标题回退“新会话”，不再用首条用户消息兜底。
    assertEquals("新会话", session.displayTitle())
  }
  @Test fun directoryFallbackUsesSegmentBoundariesAndMostSpecificAncestor() {
    val projects = listOf(Project("a", "/work/app", "A"), Project("b", "/work/app/lib", "B"))
    assertEquals("b", resolveSessionProject(Session("s", "/work/app/lib/src", "", 0), projects)?.id)
    assertNull(resolveSessionProject(Session("s", "/work/application", "", 0), projects))
  }
  @Test fun configurationRecordsAreNoticesNotBubblesAndIdleMarkersAreHidden() {
    val configuration = JSONObject("""{"id":"config","type":"model-switched","model":{"providerID":"p","id":"m"},"time":{"created":1}}""").toMessage()
    assertEquals("notice", configuration.role)
    assertEquals("已切换到 m", configuration.notice?.label)
    assertEquals("m", configuration.model?.modelId)
    val idle = JSONObject("""{"id":"idle","type":"idle","outcome":"succeeded","time":{"created":2}}""").toMessage()
    assertFalse(idle.isDisplayable)
    assertEquals(SessionContent.EMPTY, listOf(configuration, idle).sessionPreview().content)
  }
  @Test fun nativeFileAttachmentsPreserveUriMimeAndName() {
    val message = JSONObject("""{"id":"u","type":"user","text":"查看图片","files":[{"uri":"file:///project/logo.png","mime":"image/png","name":"logo.png"}],"time":{"created":1}}""").toMessage()
    val file = message.parts.single { it.type == "file" }
    assertEquals("file:///project/logo.png", file.path)
    assertEquals("image/png", file.mime)
    assertEquals("logo.png", file.title)
  }
  @Test fun terminalToolSnapshotCannotRestartACompletedTask() {
    val previous = TaskState("s", TaskPhase.COMPLETED, "done", 10, 20)
    val result = TaskReducer.event("s", "session.tool.called", JSONObject("""{"name":"shell","input":{"command":"ls"}}"""), previous)
    assertEquals(previous, result)
  }
  @Test fun finishingRecordsFinishTimeWithoutDiscardingRunStart() {
    val completed = TaskReducer.status("s", false, TaskState("s", TaskPhase.THINKING, since = 10))
    assertEquals(10L, completed.since)
    assertNotNull(completed.finishedAt)
    val resumed = TaskReducer.status("s", true, TaskState("s", TaskPhase.WAITING_PERMISSION, since = 10))
    assertEquals(10L, resumed.since)
  }
  @Test fun parentAndThreeRunningChildrenCountAsOneMainTask() {
    val tasks = mapOf("p" to TaskState("p", TaskPhase.SUBAGENT)) + (1..3).associate { "c$it" to TaskState("c$it", TaskPhase.THINKING) }
    assertEquals(1, TaskSummary.of(tasks, parents = (1..3).associate { "c$it" to "p" }).running)
    val waiting = tasks + ("c1" to TaskState("c1", TaskPhase.WAITING_PERMISSION))
    val summary = TaskSummary.of(waiting, parents = (1..3).associate { "c$it" to "p" })
    assertEquals(1, summary.waiting); assertEquals(0, summary.running)
  }

  @Test fun childResultsNeverCountAndOnlyUnreadRootResultsDo() {
    val parents = mapOf("child" to "parent")
    val finishedChild = mapOf("parent" to TaskState("parent", TaskPhase.IDLE), "child" to TaskState("child", TaskPhase.COMPLETED))
    assertEquals(TaskSummary.EMPTY, TaskSummary.of(finishedChild, emptyList(), parents))
    val unread = listOf(SessionNotice("r", "parent", 1, true))
    assertEquals(1, TaskSummary.of(finishedChild, unread, parents).failed)
    assertEquals(TaskSummary.EMPTY, TaskSummary.of(finishedChild, unread.map { it.copy(viewed = true) }, parents))
  }


  @Test fun binaryReferencesKeepTheirMimeTypes() {
    assertEquals("image/png", referenceMime("/repo/图像.PNG"))
    assertEquals("application/pdf", referenceMime("/repo/manual.pdf"))
    assertEquals("text/plain", referenceMime("/repo/main.kt"))
  }

}
