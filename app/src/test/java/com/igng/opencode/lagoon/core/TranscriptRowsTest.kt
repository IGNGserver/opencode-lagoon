package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptRowsTest {
  private val t0 = 1_700_000_000_000L

  private fun part(
    id: String, type: String, tool: String = "", text: String = "", input: String = "", output: String = "",
    status: String = "completed", patch: String = "", error: String = "", files: List<String> = emptyList()
  ) = MessagePart(id, type, text = text, tool = tool, status = status, input = input, output = output, patch = patch, error = error, files = files)

  private fun user(id: String, created: Long, text: String = "你好") =
    Message(id, "user", created, listOf(part("$id:text", "text", text = text)))

  private fun assistant(id: String, created: Long, vararg parts: MessagePart, completedAt: Long? = created + 1_000) =
    Message(id, "assistant", created, parts.toList(), completedAt = completedAt)

  private fun tool(id: String, tool: String, input: String = "", output: String = "", status: String = "completed", patch: String = "") =
    part(id, "tool", tool = tool, input = input, output = output, status = status, patch = patch)

  @Test fun userAndAssistantMessagesFormOneTurnWithSingleUserRow() {
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, part("p1", "text", text = "答案"))))
    assertEquals(1, rows.count { it.kind == "user" })
    assertEquals(1, rows.count { it.kind == "text" })
    assertEquals(1, rows.count { it.kind == "time" })
  }

  @Test fun secondUserStartsNewTurnAndTimeSeparatorNeedsAGap() {
    val close = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, part("p1", "text", text = "x")), user("u2", t0 + 60_000)))
    assertEquals(2, close.count { it.kind == "user" })
    assertEquals(1, close.count { it.kind == "time" })
    val far = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, part("p1", "text", text = "x")), user("u2", t0 + 3_600_000)))
    assertEquals(2, far.count { it.kind == "time" })
  }

  @Test fun consecutiveContextToolsFoldIntoOneGroup() {
    val parts = listOf(
      tool("t1", "read", input = """{"filePath":"a.kt"}"""),
      tool("t2", "glob", input = """{"pattern":"*.kt"}"""),
      tool("t3", "grep", input = """{"pattern":"foo"}"""),
      tool("t4", "read", input = """{"filePath":"b.kt"}""")
    )
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())))
    val groups = rows.filter { it.kind == "context-group" }
    assertEquals(1, groups.size)
    assertEquals("已收集上下文", groups.first().title)
    assertTrue(groups.first().subtitle.contains("2 个读取"))
    assertTrue(groups.first().subtitle.contains("2 个搜索"))
    assertTrue(rows.none { it.kind == "tool" })
  }

  @Test fun editBetweenContextToolsSplitsGroups() {
    val parts = listOf(
      tool("t1", "read", input = """{"filePath":"a.kt"}"""),
      tool("t2", "edit", input = """{"filePath":"a.kt"}""", patch = "@@ -1 +1 @@"),
      tool("t3", "list", input = """{"path":"src"}""")
    )
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())))
    assertEquals(2, rows.count { it.kind == "context-group" })
    assertEquals(1, rows.count { it.kind == "tool" && it.title == "编辑文件" })
  }

  @Test fun hiddenAndPendingToolsProduceNoRows() {
    val parts = listOf(
      tool("t1", "todowrite", input = """{"todos":[]}"""),
      tool("t2", "question", status = "running"),
      tool("t3", "question", status = "completed")
    )
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())))
    assertEquals(1, rows.count { it.kind == "tool" })
    assertEquals("提问", rows.first { it.kind == "tool" }.title)
  }

  @Test fun emptyMessagesProduceNoRows() {
    val rows = TranscriptRows.build(listOf(Message("m1", "assistant", t0, listOf(part("p1", "text", text = "  ")))))
    assertTrue(rows.isEmpty())
  }

  @Test fun toolRunOfEightFoldsBehindSummaryRowUntilExpanded() {
    val parts = (1..8).map { tool("t$it", "bash", input = """{"command":"cmd$it"}""", output = "ok") }
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())))
    val summary = rows.single { it.kind == "tool-summary" }
    assertEquals("已处理 8 个操作", summary.title)
    assertTrue(rows.none { it.kind == "tool" })
    val expanded = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())), setOf(summary.key))
    assertEquals(8, expanded.count { it.kind == "tool" })
  }

  @Test fun toolRowsDeriveTitleSubtitleAndSections() {
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "bash", input = """{"command":"ls -la"}""", output = "file1\nfile2"))))
    val row = rows.single { it.kind == "tool" }
    assertEquals("命令行", row.title)
    assertEquals("ls -la", row.subtitle)
    val expanded = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "bash", input = """{"command":"ls -la"}""", output = "file1"))), setOf(row.key))
    val body = expanded.filter { it.kind == "tool-body" }
    assertTrue(body.isNotEmpty())
    assertTrue(body.first().text.contains("$ ls -la"))
    assertTrue(body.first().text.contains("file1"))
  }

  @Test fun readToolUsesFilenameAsSubtitle() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "read", input = """{"filePath":"src/Main.kt"}""")))
    val group = TranscriptRows.build(messages).single { it.kind == "context-group" }
    val item = TranscriptRows.build(messages, setOf(group.key)).first { it.kind == "context-item" }
    assertEquals("读取文件", item.title)
    assertEquals("Main.kt", item.subtitle)
  }

  @Test fun contextGroupExpandsToCompactItems() {
    val parts = listOf(tool("t1", "read", input = """{"filePath":"a.kt"}"""), tool("t2", "grep", input = """{"pattern":"foo","include":"*.kt"}"""))
    val base = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())))
    val group = base.single { it.kind == "context-group" }
    assertTrue(base.none { it.kind == "context-item" })
    val expanded = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())), setOf(group.key))
    assertEquals(2, expanded.count { it.kind == "context-item" })
    assertTrue(expanded.first { it.kind == "context-item" && it.title == "搜索内容" }.args.contains("include=*.kt"))
  }

  @Test fun diffSummaryAggregatesEditedFiles() {
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "edit", input = """{"filePath":"a.kt"}""", patch = "@@ -1 +1 @@"))))
    val summary = rows.single { it.kind == "diff-summary" }
    assertTrue(summary.title.contains("1 个文件"))
    val expanded = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "edit", input = """{"filePath":"a.kt"}""", patch = "@@ -1 +1 @@"))), setOf(summary.key))
    assertTrue(expanded.any { it.kind == "diff-file" && it.title == "a.kt" })
  }

  @Test fun thinkingRowOnlyWhileWorkingWithoutAssistantContent() {
    val pending = TranscriptRows.build(listOf(user("u1", t0)), working = true)
    assertTrue(pending.any { it.kind == "thinking" })
    val done = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, part("p1", "text", text = "x"))), working = true)
    assertTrue(done.none { it.kind == "thinking" })
  }

  @Test fun metaRowCarriesDurationAndCopyText() {
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0, part("p1", "text", text = "答案"), completedAt = t0 + 5_000)))
    val meta = rows.single { it.kind == "meta" }
    assertTrue(meta.meta.contains("用时 5 秒"))
    assertEquals("答案", meta.copyText)
  }

  @Test fun errorEnvelopeIsUnwrappedAndAbortBecomesDivider() {
    assertEquals("boom", TranscriptRows.unwrapError("""{"error":{"type":"ApiError","message":"boom"}}"""))
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, part("p1", "text", text = "x"), completedAt = null)
      .copy(finish = "aborted")))
    assertTrue(rows.any { it.kind == "divider" && it.text.contains("中断") })
    assertTrue(rows.none { it.kind == "error" })
  }

  private fun assertUniqueKeys(rows: List<TranscriptRow>) {
    val duplicates = rows.groupBy { it.key }.filterValues { it.size > 1 }.keys
    assertTrue("duplicate LazyColumn keys: $duplicates", duplicates.isEmpty())
  }

  @Test fun v2TextAndReasoningWithoutIdsInOneMessageGetDistinctKeys() {
    // Official V2 schema: Assistant.Text / Assistant.Reasoning carry no id field.
    val message = JSONObject("""{"id":"msg_a","type":"assistant","time":{"created":$t0},"agent":"build","model":{"id":"m","providerID":"p"},
      "content":[{"type":"reasoning","text":"想一想"},{"type":"text","text":"答案"},{"type":"text","text":"补充"}]}""").toMessage()
    assertEquals(listOf("msg_a:reasoning:0", "msg_a:text:0", "msg_a:text:1"), message.parts.map { it.id })
    val rows = TranscriptRows.build(listOf(user("u1", t0), message))
    assertEquals(1, rows.count { it.kind == "reasoning" })
    assertEquals(2, rows.count { it.kind == "text" })
    assertUniqueKeys(rows)
  }

  @Test fun idlessPartsAreKeyedByPositionWhenTheParserDidNotAssignIds() {
    val rows = TranscriptRows.build(listOf(user("u1", t0),
      assistant("a1", t0 + 500, part("", "reasoning", text = "r"), part("", "text", text = "x"), part("", "text", text = "y"))))
    assertEquals(3, rows.count { it.kind == "reasoning" || it.kind == "text" })
    assertUniqueKeys(rows)
  }

  @Test fun toolCallIdReusedAcrossMessagesOfOneTurnDoesNotCollide() {
    // Real data: the provider returned call_1966253 twice within one turn, each after a reasoning part.
    val messages = listOf(user("u1", t0),
      assistant("a1", t0 + 500, part("a1#0", "reasoning", text = "查找"), tool("call_1966253", "grep", input = """{"pattern":"x"}""")),
      assistant("a2", t0 + 900, part("a2#0", "reasoning", text = "再查"), tool("call_1966253", "grep", input = """{"pattern":"y"}""")),
      assistant("a3", t0 + 1_300, tool("call_7", "bash", input = """{"command":"ls"}"""), tool("call_7", "bash", input = """{"command":"pwd"}""")))
    val rows = TranscriptRows.build(messages)
    assertEquals(2, rows.count { it.kind == "context-group" })
    assertEquals(2, rows.count { it.kind == "tool" })
    assertUniqueKeys(rows)
    val expanded = TranscriptRows.build(messages, rows.filter { it.kind == "context-group" || it.kind == "tool" }.map { it.key }.toSet())
    assertEquals(2, expanded.count { it.kind == "context-item" })
    assertUniqueKeys(expanded)
  }

  @Test fun duplicateMessagesStillProduceUniqueKeys() {
    val answer = assistant("a1", t0 + 500, part("p1", "text", text = "答案"), tool("t1", "edit", input = """{"filePath":"a.kt"}""", patch = "@@ -1 +1 @@"))
    val rows = TranscriptRows.build(listOf(user("u1", t0), answer, answer, user("u1", t0 + 4_000_000), answer))
    assertUniqueKeys(rows)
    val summary = rows.first { it.kind == "diff-summary" }
    assertUniqueKeys(TranscriptRows.build(listOf(user("u1", t0), answer, answer), setOf(summary.key)))
  }

  @Test fun keysAreStableAcrossRebuildsSoExpansionSurvivesUpdates() {
    val first = listOf(user("u1", t0), assistant("a1", t0 + 500, part("a1#0", "reasoning", text = "r"), tool("call_1", "bash", input = """{"command":"ls"}""", output = "x")))
    val toolKey = TranscriptRows.build(first).single { it.kind == "tool" }.key
    val grown = first + assistant("a2", t0 + 900, part("a2#0", "text", text = "完成"))
    val rebuilt = TranscriptRows.build(grown, setOf(toolKey))
    assertEquals(toolKey, rebuilt.single { it.kind == "tool" }.key)
    assertTrue(rebuilt.any { it.kind == "tool-body" })
  }

  @Test fun workingPlaceholderSaysRunningNotThinking() {
    val pending = TranscriptRows.build(listOf(user("u1", t0)), working = true).single { it.kind == "thinking" }
    assertEquals("运行中…", pending.text)
  }

  @Test fun errorRowShowsReadableMessage() {
    val rows = TranscriptRows.build(listOf(user("u1", t0), Message("a1", "assistant", t0 + 500, listOf(part("p1", "text", text = "x")), error = "Error: boom")))
    assertEquals(1, rows.count { it.kind == "error" })
    assertTrue(rows.first { it.kind == "error" }.text.contains("boom"))
    assertFalse(rows.first { it.kind == "error" }.text.startsWith("Error:"))
  }

  private fun notice(id: String, created: Long, type: String, json: String) = JSONObject("""{"id":"$id","type":"$type","time":{"created":$created},$json}""").toMessage()

  @Test fun modelFacingRecordsRenderAsOneLineNoticesNotReplies() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 100, part("p1", "text", text = "开始")),
      notice("s1", t0 + 200, "system", """"text":"tools.playwright.browser_close(): Promise<unknown>","description":"Instructions updated: mcp:playwright","metadata":{"notice":"instructions","instructionSources":["mcp:playwright"]}"""),
      notice("k1", t0 + 300, "skill", """"skill":"sk","name":"release","text":"# SKILL.md 全文" """),
      notice("y1", t0 + 400, "synthetic", """"text":"model only" """))
    val rows = TranscriptRows.build(messages)
    val notes = rows.filter { it.kind == "note" }
    assertEquals(listOf("指令已更新", "技能"), notes.map { it.title })
    assertEquals("mcp:playwright", notes[0].text); assertEquals("release", notes[1].text)
    assertEquals(listOf("开始"), rows.filter { it.kind == "text" }.map { it.text })
    assertTrue(rows.none { it.text.contains("Promise<unknown>") || it.text.contains("SKILL.md") || it.text.contains("model only") })
  }

  @Test fun shellCommandsFormTheirOwnTurn() {
    val shell = JSONObject("""{"id":"sh1","type":"shell","shellID":"sh_1","command":"ls","status":"exited","exit":2,"output":{"output":"nope","cursor":4,"size":4,"truncated":false},"time":{"created":${t0 + 10}}}""").toMessage()
    val rows = TranscriptRows.build(listOf(user("u1", t0), shell, assistant("a1", t0 + 20, part("p1", "text", text = "后续"))))
    assertEquals(1, rows.count { it.kind == "user" })
    val tool = rows.single { it.kind == "tool" }
    assertEquals("命令行", tool.title); assertEquals("ls", tool.subtitle); assertEquals("error", tool.status)
    // The assistant after a shell turn starts its own turn instead of joining the shell.
    assertEquals(2, rows.count { it.kind == "meta" } + rows.count { it.kind == "tool" })
  }

  @Test fun onlyTheLastAssistantErrorIsShownAndInterruptionsAreDividers() {
    val first = Message("a1", "assistant", t0 + 1, listOf(part("p1", "text", text = "x")), error = "rate limited", errorType = "provider", completedAt = t0 + 2)
    val last = Message("a2", "assistant", t0 + 3, listOf(part("p2", "text", text = "y")), error = "Interrupted by user", errorType = "session.interrupted", completedAt = t0 + 4)
    val rows = TranscriptRows.build(listOf(user("u1", t0), first, last))
    assertTrue(rows.none { it.kind == "error" })
    assertEquals(1, rows.count { it.kind == "divider" && it.text == "本轮已中断" })
    val failing = TranscriptRows.build(listOf(user("u1", t0), first.copy(error = null), last.copy(error = "boom", errorType = "provider")))
    assertEquals(listOf("boom"), failing.filter { it.kind == "error" }.map { it.text })
  }

  @Test fun aStagedRevertHidesItsBoundaryAndEverythingAfter() {
    val messages = listOf(user("msg_1", t0), assistant("msg_2", t0 + 1, part("p", "text", text = "a")), user("msg_3", t0 + 2, "撤销我"), assistant("msg_4", t0 + 3, part("q", "text", text = "b")))
    val rows = TranscriptRows.build(messages, revertMessageId = "msg_3")
    assertEquals(1, rows.count { it.kind == "user" })
    assertTrue(rows.none { it.text == "撤销我" || it.text == "b" })
  }

  @Test fun queuedInputIsMarkedAndSubagentToolsLinkToTheirChild() {
    val queued = user("msg_q", t0 + 5, "下一步").copy(queued = true)
    val subagent = part("call_s", "tool", tool = "subagent", input = """{"agent":"explore","description":"查找调用"}""", status = "running").copy(target = "ses_child")
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 1, subagent, completedAt = null), queued), working = true)
    assertTrue(rows.single { it.kind == "user" && it.text == "下一步" }.meta.startsWith("排队中"))
    val tool = rows.single { it.kind == "tool" }
    assertEquals("ses_child", tool.target); assertEquals("explore · 查找调用", tool.subtitle)
  }

  @Test fun editDiffsComeFromToolMetadata() {
    val edit = JSONObject("""{"type":"tool","id":"call_e","name":"edit","state":{"status":"completed","input":{"path":"src/a.kt","oldString":"a","newString":"b"},"content":[{"type":"text","text":"ok"}],
      "metadata":{"files":[{"file":"src/a.kt","patch":"@@ -1 +1 @@\n-a\n+b","additions":1,"deletions":1,"status":"modified"}]}},"time":{"created":1}}""").toAssistantPart("call_e")
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 1, edit)))
    assertEquals("a.kt", rows.single { it.kind == "tool" }.subtitle)
    val summary = rows.single { it.kind == "diff-summary" }
    assertEquals("本轮改动 1 个文件", summary.title)
  }

  @Test fun halfSheetDetailsReturnTheGroupChildren() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, tool("t1", "bash", input = """{"command":"ls -la"}""", output = "file.txt")))
    val row = TranscriptRows.build(messages).single { it.kind == "tool" }
    val details = TranscriptRows.details(messages, row.key)
    assertTrue(details.isNotEmpty())
    assertTrue(details.all { it.detailOf == row.key })
    assertTrue(details.any { it.kind == "tool-body" && it.text.contains("ls -la") })
  }

  @Test fun halfSheetDetailsForContextGroupReturnItems() {
    val parts = listOf(tool("t1", "read", input = """{"filePath":"a.kt"}"""), tool("t2", "read", input = """{"filePath":"b.kt"}"""))
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray()))
    val group = TranscriptRows.build(messages).single { it.kind == "context-group" }
    val details = TranscriptRows.details(messages, group.key)
    assertEquals(2, details.count { it.kind == "context-item" })
    assertTrue(details.all { it.detailOf == group.key })
  }

  @Test fun halfSheetDetailsForDiffSummaryReturnFiles() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "edit", input = """{"filePath":"a.kt"}""", patch = "@@ -1 +1 @@\n-old\n+new")))
    val summary = TranscriptRows.build(messages).single { it.kind == "diff-summary" }
    val details = TranscriptRows.details(messages, summary.key)
    assertTrue(details.any { it.kind == "diff-file" && it.title == "a.kt" })
    assertTrue(details.any { it.kind == "tool-body" })
    assertTrue(details.all { it.detailOf == summary.key })
  }
}
