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
  @Test fun aForegroundMissNeverFinishesABackgroundedRunButAuthoritativeIdleDoes() {
    val running = TaskState("s", TaskPhase.THINKING, since = 10, activeAt = 15)
    // 不在 /api/session/active 不等于本轮结束：后台任务仍在跑时保留运行态。
    assertEquals(running, TaskReducer.status("s", false, running))
    // 服务端 time.idle 越过本轮起点时才权威收尾，且保留运行起点。
    val completed = TaskReducer.terminal("s", "succeeded", running)
    assertEquals(10L, completed.since)
    assertEquals(15L, completed.finishedAt)
    // 前台读取仍会保留“等待输入”的粘性。
    val permission = TaskState("s", TaskPhase.WAITING_PERMISSION, since = 10)
    assertEquals(permission, TaskReducer.status("s", true, permission))
  }

  @Test fun backgroundedWorkKeepsTheSessionRunningUntilItWakesTheAgentAgain() {
    val started = TaskReducer.event("s", "session.execution.started", JSONObject("{}"), null, 100)!!
    assertTrue(started.active)
    val backgrounded = TaskReducer.event("s", "session.synthetic",
      JSONObject("""{"sessionID":"s","text":"The backgrounded work is still unfinished. Move on to other work."}"""), started, 120)!!
    assertTrue(backgrounded.background)
    // 根执行 settle 不代表会话结束：后台工作还在跑。
    val settled = TaskReducer.event("s", "session.execution.succeeded", JSONObject("{}"), backgrounded, 130)!!
    assertTrue(settled.active)
    assertTrue(settled.background)
    // 后台完成重新唤醒 AI（新的 execution.started）后清除后台标记，最终正常收尾。
    val resumed = TaskReducer.event("s", "session.execution.started", JSONObject("{}"), settled, 200)!!
    assertFalse(resumed.background)
    assertEquals(TaskPhase.COMPLETED, TaskReducer.event("s", "session.execution.succeeded", JSONObject("{}"), resumed, 240)!!.phase)
  }

  @Test fun aSessionWithBackgroundedWorkCountsAsBackgroundRunning() {
    val parents = mapOf("child" to "root")
    val childRunning = mapOf("root" to TaskState("root", TaskPhase.COMPLETED), "child" to TaskState("child", TaskPhase.THINKING))
    assertEquals(setOf("root"), TaskSummary.backgroundRoots(childRunning, parents))
    val sameSession = mapOf("root" to TaskState("root", TaskPhase.THINKING, background = true))
    assertEquals(setOf("root"), TaskSummary.backgroundRoots(sameSession, emptyMap()))
  }

  @Test fun anActiveReadNeverDowngradesAPendingPrompt() {
    // 服务器仍在执行时应保留“等待输入”，否则权限/表单枚举的瞬时抖动会让会话在等待/运行之间跳。
    val permission = TaskState("s", TaskPhase.WAITING_PERMISSION, "等待权限确认", 10)
    assertEquals(permission, TaskReducer.status("s", true, permission))
    val question = TaskState("s", TaskPhase.WAITING_QUESTION, "等待你的回答", 10)
    assertEquals(question, TaskReducer.status("s", true, question))
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
