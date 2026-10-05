package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Live projection of OpenCode 2.x `/api/event` frames, checked against the official reducer's semantics. */
class TranscriptProjectionTest {
  private var sequence = 0
  private fun event(type: String, data: String) = ServerEvent("evt_${++sequence}", "/p", type, JSONObject(data), created = 1_000L + sequence)
  private fun List<Message>.apply(type: String, data: String) = TranscriptProjection.apply(this, event(type, data))!!.messages

  @Test fun aStepStreamsTextAndReasoningIntoOneAssistantMessage() {
    val messages = listOf(Message("msg_u", "user", 1, listOf(MessagePart("msg_u:text", "text", text = "hi"))))
      .apply("session.step.started", """{"sessionID":"s","assistantMessageID":"msg_a","agent":"build","model":{"providerID":"p","id":"m"},"started":5}""")
      .apply("session.reasoning.started", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0}""")
      .apply("session.reasoning.delta", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0,"delta":"想"}""")
      .apply("session.reasoning.ended", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0,"text":"想一想"}""")
      .apply("session.text.started", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0}""")
      .apply("session.text.delta", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0,"delta":"你"}""")
      .apply("session.text.delta", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0,"delta":"好"}""")
    val assistant = messages.last()
    assertEquals("msg_a", assistant.id); assertEquals("build", assistant.agent); assertEquals("m", assistant.model?.modelId)
    assertEquals(listOf("msg_a:reasoning:0", "msg_a:text:0"), assistant.parts.map { it.id })
    assertEquals("想一想", assistant.parts[0].text); assertEquals("你好", assistant.parts[1].text)
    val ended = messages.apply("session.text.ended", """{"sessionID":"s","assistantMessageID":"msg_a","ordinal":0,"text":"你好！"}""")
      .apply("session.step.ended", """{"sessionID":"s","assistantMessageID":"msg_a","finish":"stop","cost":0,"tokens":{}}""")
    assertEquals("你好！", ended.last().parts.last().text)
    assertNotNull(ended.last().completedAt)
  }

  @Test fun eventsForAnAssistantThatWasNeverLoadedAreDropped() {
    val result = TranscriptProjection.apply(emptyList(), event("session.text.delta", """{"sessionID":"s","assistantMessageID":"msg_x","ordinal":0,"delta":"lost"}"""))!!
    assertTrue(result.messages.isEmpty())
    assertFalse(result.reconcile)
  }

  @Test fun toolLifecycleProjectsInputOutputAndEditDiffs() {
    val base = listOf(Message("msg_a", "assistant", 1, emptyList(), type = "assistant"))
      .apply("session.tool.input.started", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_1","name":"edit"}""")
    assertEquals("pending", base.single().parts.single().status)
    val called = base.apply("session.tool.called", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_1","input":{"path":"src/a.kt","oldString":"a","newString":"b"},"executed":true}""")
    assertEquals("running", called.single().parts.single().status)
    assertEquals("src/a.kt", called.single().parts.single().path)
    val done = called.apply("session.tool.success", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_1","content":[{"type":"text","text":"edited"}],
      "metadata":{"files":[{"file":"src/a.kt","patch":"@@ -1 +1 @@","additions":1,"deletions":1,"status":"modified"}],"replacements":1},"executed":true}""")
    val tool = done.single().parts.single()
    assertEquals("completed", tool.status); assertEquals("edited", tool.output)
    assertEquals(listOf("src/a.kt"), tool.files); assertEquals("@@ -1 +1 @@", tool.patch)
  }

  @Test fun aShellCommandWithANonZeroExitIsAFailure() {
    val done = listOf(Message("msg_a", "assistant", 1, emptyList(), type = "assistant"))
      .apply("session.tool.input.started", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_1","name":"shell"}""")
      .apply("session.tool.called", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_1","input":{"command":"false"},"executed":true}""")
      .apply("session.tool.success", """{"sessionID":"s","assistantMessageID":"msg_a","id":"call_1","content":[{"type":"text","text":""}],"metadata":{"exit":1},"executed":true}""")
    assertEquals("error", done.single().parts.single().status)
  }

  @Test fun instructionUpdatesBecomeNoticesNeverReplies() {
    val messages = listOf(Message("msg_a", "assistant", 1, emptyList(), type = "assistant"))
      .apply("session.instructions.updated", """{"sessionID":"s","delta":{"mcp:playwright":{}},"text":"tools.playwright.browser_close(): Promise<unknown>"}""")
    val notice = messages.last()
    assertEquals("notice", notice.role)
    assertEquals("指令已更新", notice.notice?.label)
    assertEquals(listOf("mcp:playwright"), notice.notice?.items)
    assertTrue(notice.parts.isEmpty())
    val rows = TranscriptRows.build(messages)
    assertTrue(rows.none { it.text.contains("Promise<unknown>") })
  }

  @Test fun inboxInputAppearsQueuedAndMovesToTheEndWhenDelivered() {
    val pending = listOf(Message("msg_a", "assistant", 1, emptyList(), type = "assistant"))
      .apply("session.inbox.enqueued", """{"sessionID":"s","inboxID":"msg_q","item":{"type":"user","delivery":"queue","payload":{"text":"然后呢"}}}""")
    assertTrue(pending.last().queued)
    val delivered = (pending + Message("msg_b", "assistant", 3, emptyList(), type = "assistant"))
      .apply("session.inbox.delivered", """{"sessionID":"s","inboxID":"msg_q"}""")
    assertEquals("msg_q", delivered.last().id)
    assertFalse(delivered.last().queued)
    val cancelled = pending.apply("session.inbox.cancelled", """{"sessionID":"s","inboxID":"msg_q"}""")
    assertEquals(listOf("msg_a"), cancelled.map { it.id })
  }

  @Test fun committedRevertDropsTheMessagesAfterItsBoundary() {
    val messages = listOf("msg_1", "msg_2", "msg_3").map { Message(it, "user", 1, emptyList()) }
    assertEquals(listOf("msg_1"), messages.apply("session.revert.committed", """{"sessionID":"s","to":"msg_2"}""").map { it.id })
  }

  @Test fun aRunEndingWithToolsInFlightAsksForReconciliation() {
    val running = listOf(Message("msg_a", "assistant", 1, listOf(MessagePart("call_1", "tool", tool = "shell", status = "running")), type = "assistant"))
    val result = TranscriptProjection.apply(running, event("session.execution.succeeded", """{"sessionID":"s"}"""))!!
    assertTrue(result.reconcile)
  }

  @Test fun subagentResultsLinkToTheChildSession() {
    val messages = emptyList<Message>()
      .apply("session.synthetic", """{"sessionID":"s","text":"<task result>…</task result>","description":"找到 3 处调用","metadata":{"source":"subagent","state":"completed","agent":"explore","childID":"ses_child"}}""")
    assertEquals("explore 已完成", messages.single().notice?.label)
    assertEquals("ses_child", messages.single().notice?.target)
    // Synthetic input without a description is not a transcript row.
    val hidden = emptyList<Message>().apply("session.synthetic", """{"sessionID":"s","text":"model-only context"}""")
    assertFalse(hidden.single().isDisplayable)
  }
}
