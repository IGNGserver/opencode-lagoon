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

  @Test fun consecutiveToolsGroupByCategory() {
    val parts = listOf(
      tool("t1", "read", input = """{"filePath":"a.kt"}"""),
      tool("t2", "glob", input = """{"pattern":"*.kt"}"""),
      tool("t3", "grep", input = """{"pattern":"foo"}"""),
      tool("t4", "read", input = """{"filePath":"b.kt"}""")
    )
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())))
    assertEquals(listOf("使用了 1 个 读取", "使用了 2 个 搜索", "使用了 1 个 读取"), rows.filter { it.kind == "tool-group" }.map { it.title })
    assertTrue(rows.none { it.kind == "tool" })
  }

  @Test fun editBetweenToolsSplitsGroups() {
    val parts = listOf(
      tool("t1", "read", input = """{"filePath":"a.kt"}"""),
      tool("t2", "edit", input = """{"filePath":"a.kt"}""", patch = "@@ -1 +1 @@"),
      tool("t3", "list", input = """{"path":"src"}""")
    )
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())))
    assertEquals(listOf("使用了 1 个 读取", "使用了 1 个 编辑", "使用了 1 个 列表"), rows.filter { it.kind == "tool-group" }.map { it.title })
    assertTrue(rows.none { it.kind == "tool" })
  }

  @Test fun hiddenAndPendingToolsProduceNoRows() {
    val parts = listOf(
      tool("t1", "todowrite", input = """{"todos":[]}"""),
      tool("t2", "question", status = "running"),
      tool("t3", "question", status = "completed")
    )
    val rows = TranscriptRows.build(listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray())))
    assertEquals(1, rows.count { it.kind == "tool-group" })
    assertEquals("使用了 1 个 提问", rows.first { it.kind == "tool-group" }.title)
  }

  @Test fun emptyMessagesProduceNoRows() {
    val rows = TranscriptRows.build(listOf(Message("m1", "assistant", t0, listOf(part("p1", "text", text = "  ")))))
    assertTrue(rows.isEmpty())
  }

  @Test fun aRunOfSameCategoryToolsFoldsIntoOneGroup() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, *(1..8).map { tool("t$it", "bash", input = """{"command":"cmd$it"}""", output = "ok") }.toTypedArray()))
    val rows = TranscriptRows.build(messages)
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("使用了 8 个 Shell", group.title)
    assertTrue(rows.none { it.kind == "tool" })
    assertEquals(8, TranscriptRows.details(messages, group.key).count { it.kind == "tool" })
  }

  @Test fun halfSheetCarriesToolTitleSubtitleAndBody() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "bash", input = """{"command":"ls -la"}""", output = "file1\nfile2")))
    val group = TranscriptRows.build(messages).single { it.kind == "tool-group" }
    val details = TranscriptRows.details(messages, group.key)
    val item = details.single { it.kind == "tool" }
    assertEquals("命令行", item.title)
    assertEquals("ls -la", item.subtitle)
    val body = details.filter { it.kind == "tool-body" }
    assertTrue(body.isNotEmpty())
    // The body is attached to the item header so the sheet can keep it collapsed until tapped.
    assertEquals(item.key, body.first().bodyOf)
    assertTrue(body.first().text.contains("$ ls -la"))
    assertTrue(body.first().text.contains("file1"))
  }

  @Test fun readToolUsesFilenameAsSubtitle() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500,
      tool("t1", "read", input = """{"filePath":"src/Main.kt"}""")))
    val group = TranscriptRows.build(messages).single { it.kind == "tool-group" }
    val item = TranscriptRows.details(messages, group.key).first { it.kind == "tool" }
    assertEquals("读取文件", item.title)
    assertEquals("Main.kt", item.subtitle)
  }

  @Test fun eachCategoryGroupCarriesItsOwnCompactItems() {
    val parts = listOf(tool("t1", "read", input = """{"filePath":"a.kt"}"""), tool("t2", "grep", input = """{"pattern":"foo","include":"*.kt"}"""))
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray()))
    val base = TranscriptRows.build(messages)
    assertTrue(base.none { it.kind == "tool" })
    val search = base.first { it.kind == "tool-group" && it.title == "使用了 1 个 搜索" }
    val searchItem = TranscriptRows.details(messages, search.key).first { it.kind == "tool" }
    assertEquals("搜索内容", searchItem.title)
    assertTrue(searchItem.args.contains("include=*.kt"))
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

  @Test fun runningTurnOmitsTheStaticDuration() {
    // Between steps every existing assistant message may already be "completed"; showing a frozen 用时
    // there reads as “已结束”, so the running turn must not render it.
    val messages = listOf(user("u1", t0), assistant("a1", t0, part("p1", "text", text = "x"), completedAt = t0 + 5_000))
    assertFalse(TranscriptRows.build(messages, working = true).single { it.kind == "meta" }.meta.contains("用时"))
    assertTrue(TranscriptRows.build(messages, working = false).single { it.kind == "meta" }.meta.contains("用时 5 秒"))
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
    // All greps fold into one「搜索」group even though the call id repeats; the shells form another.
    assertEquals(listOf("使用了 2 个 搜索", "使用了 2 个 Shell"), rows.filter { it.kind == "tool-group" }.map { it.title })
    assertTrue(rows.none { it.kind == "tool" })
    assertUniqueKeys(rows)
    val details = rows.filter { it.kind == "tool-group" }.flatMap { TranscriptRows.details(messages, it.key) }
    assertEquals(4, details.count { it.kind == "tool" })
    // The two 思考 entries beside the greps travel with the group.
    assertEquals(2, details.count { it.kind == "reasoning" })
    assertUniqueKeys(details)
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
    val groupKey = TranscriptRows.build(first).single { it.kind == "tool-group" }.key
    val grown = first + assistant("a2", t0 + 900, part("a2#0", "text", text = "完成"))
    val rebuilt = TranscriptRows.build(grown, setOf(groupKey))
    assertEquals(groupKey, rebuilt.single { it.kind == "tool-group" }.key)
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
    val tool = rows.single { it.kind == "tool-group" }
    assertEquals("ses_child", tool.target); assertEquals("explore · 查找调用", tool.subtitle)
  }

  @Test fun editDiffsComeFromToolMetadata() {
    val edit = JSONObject("""{"type":"tool","id":"call_e","name":"edit","state":{"status":"completed","input":{"path":"src/a.kt","oldString":"a","newString":"b"},"content":[{"type":"text","text":"ok"}],
      "metadata":{"files":[{"file":"src/a.kt","patch":"@@ -1 +1 @@\n-a\n+b","additions":1,"deletions":1,"status":"modified"}]}},"time":{"created":1}}""").toAssistantPart("call_e")
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 1, edit))
    val rows = TranscriptRows.build(messages)
    val group = rows.single { it.kind == "tool-group" }
    assertEquals("a.kt", TranscriptRows.details(messages, group.key).single { it.kind == "tool" }.subtitle)
    val summary = rows.single { it.kind == "diff-summary" }
    assertEquals("本轮改动 1 个文件", summary.title)
  }

  @Test fun halfSheetDetailsReturnTheGroupChildren() {
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, tool("t1", "bash", input = """{"command":"ls -la"}""", output = "file.txt")))
    val row = TranscriptRows.build(messages).single { it.kind == "tool-group" }
    val details = TranscriptRows.details(messages, row.key)
    assertTrue(details.isNotEmpty())
    assertTrue(details.all { it.detailOf == row.key })
    assertTrue(details.any { it.kind == "tool" && it.bodyOf == null })
    assertTrue(details.any { it.kind == "tool-body" && it.text.contains("ls -la") && it.bodyOf != null })
  }

  @Test fun halfSheetDetailsForContextGroupReturnItems() {
    val parts = listOf(tool("t1", "read", input = """{"filePath":"a.kt"}"""), tool("t2", "read", input = """{"filePath":"b.kt"}"""))
    val messages = listOf(user("u1", t0), assistant("a1", t0 + 500, *parts.toTypedArray()))
    val group = TranscriptRows.build(messages).single { it.kind == "tool-group" }
    val details = TranscriptRows.details(messages, group.key)
    assertEquals(2, details.count { it.kind == "tool" })
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
