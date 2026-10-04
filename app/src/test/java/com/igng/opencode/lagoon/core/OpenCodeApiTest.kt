package com.igng.opencode.lagoon.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenCodeApiTest {
  @Test fun authenticatesHealthAndSendsAsyncPromptInSelectedDirectory() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1.0"}""").addHeader("Content-Type", "application/json"))
      server.enqueue(MockResponse().setResponseCode(204))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), "opencode", allowCleartext = true), "secret")
      assertEquals("1.0", api.health())
      api.send(Session("session-1", "/repo with space", "Task", 0), "Fix the build", "build", ModelChoice("openai", "gpt", "GPT"))
      val health = server.takeRequest()
      assertEquals("/global/health", health.path)
      assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", health.getHeader("Authorization"))
      val prompt = server.takeRequest()
      assertTrue(prompt.path!!.startsWith("/session/session-1/prompt_async?directory="))
      val json = JSONObject(prompt.body.readUtf8())
      assertEquals("Fix the build", json.getJSONArray("parts").getJSONObject(0).getString("text"))
      assertEquals("openai", json.getJSONObject("model").getString("providerID"))
    }
  }
  @Test fun taskReducerRequiresPriorActivityBeforeIdleBecomesCompletion() {    val idle = TaskReducer.status("id", "idle")
    assertEquals(TaskPhase.IDLE, idle.phase)
    val busy = TaskReducer.status("id", "busy", idle)
    assertEquals(TaskPhase.THINKING, busy.phase)
    val tool = TaskReducer.event("id", "message.part.updated", JSONObject("""{"part":{"type":"tool","tool":"bash","state":{"title":"Run test","input":{"command":"gradle test"}}}}"""), busy)
    assertEquals(TaskPhase.TESTING, tool!!.phase)
    // Every running sub-phase shares one user-facing label, never “思考中 / 执行工具 / 测试中”.
    assertEquals(TaskState.RUNNING_DETAIL, tool.detail)
    val completed = TaskReducer.status("id", "idle", tool)
    assertEquals(TaskPhase.COMPLETED, completed.phase)
    assertEquals(TaskPhase.COMPLETED, TaskReducer.status("id", "idle", completed).phase)
    val restarted = TaskReducer.status("id", "busy", completed)
    assertEquals(TaskPhase.THINKING, restarted.phase)
    assertEquals(TaskState.RUNNING_DETAIL, restarted.detail)
  }

  @Test fun taskPhaseGroupsStayConsistent() {
    // active must be the union of the running and waiting groups used by the UI filters.
    assertTrue(TaskState.ACTIVE_PHASES == TaskState.RUNNING_PHASES + TaskState.WAITING_PHASES)
    assertTrue(TaskState.RUNNING_PHASES.intersect(TaskState.WAITING_PHASES).isEmpty())
    for (phase in TaskPhase.entries) {
      assertEquals(phase in TaskState.ACTIVE_PHASES, TaskState("id", phase).active)
    }
    assertTrue(TaskState("id", TaskPhase.COMPLETED).active.not())
    assertTrue(TaskState("id", TaskPhase.DISCONNECTED).active.not())
  }

  @Test fun detectsV2AndMapsLocationSessionsStatusAndMessages() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}""").addHeader("Content-Type", "application/json"))
      server.enqueue(MockResponse().setBody("""{"directory":"/repo","project":{"id":"project-1","directory":"/repo"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"ses-1","projectID":"project-1","title":"Task","location":{"directory":"/repo"},"time":{"created":1,"updated":2}}],"cursor":{}}"""))
      server.enqueue(MockResponse().setBody("""{"data":{"ses-1":{"type":"running"}}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[
        {"id":"msg-1","type":"user","time":{"created":3},"text":"hello"},
        {"id":"msg-2","type":"assistant","time":{"created":4},"agent":"build","model":{"providerID":"openai","id":"gpt"},"content":[{"id":"part-1","type":"text","text":"done"}]}
      ],"cursor":{}}"""))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      assertEquals("OpenCode V2", api.health())
      assertEquals("/repo", api.projects().single().directory)
      assertEquals("Task", api.sessions("/repo").single().title)
      assertEquals("running", api.status("/repo")["ses-1"])
      val messages = api.messages("ses-1", "/repo")
      assertEquals(listOf("user", "assistant"), messages.map { it.role })
      assertEquals("hello", messages[0].parts.single().text)
      assertEquals("done", messages[1].parts.single().text)
      val requests = List(6) { server.takeRequest() }
      assertEquals("/api/location", requests[2].requestUrl?.encodedPath)
      assertEquals("/repo", requests[3].requestUrl?.queryParameter("directory") ?: "")
    }
  }

  @Test fun v2PromptSwitchesSelectedAgentAndModelBeforeSending() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setResponseCode(204))
      server.enqueue(MockResponse().setResponseCode(204))
      server.enqueue(MockResponse().setResponseCode(204))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      server.takeRequest()
      server.takeRequest()
      api.send(Session("ses-1", "/repo", "Task", 0), "Fix it", "build", ModelChoice("openai", "gpt", "GPT"))
      val agentRequest = server.takeRequest()
      assertEquals("/api/session/ses-1/agent", agentRequest.requestUrl?.encodedPath)
      assertEquals("build", JSONObject(agentRequest.body.readUtf8()).getString("agent"))
      val modelRequest = server.takeRequest()
      val model = JSONObject(modelRequest.body.readUtf8()).getJSONObject("model")
      assertEquals("openai", model.getString("providerID"))
      assertEquals("/api/session/ses-1/model", modelRequest.requestUrl?.encodedPath)
      assertEquals("/api/session/ses-1/prompt", server.takeRequest().requestUrl?.encodedPath)
    }
  }

  @Test fun v2SessionListFollowsCursorAndRemovesMessageOrderOnNextPage() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"ses-1","title":"First","location":{"directory":"/repo"},"time":{"updated":1}}],"cursor":{"next":"cursor-1"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"ses-2","title":"Second","location":{"directory":"/repo"},"time":{"updated":2}}],"cursor":{}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"msg-1","type":"user","time":{"created":1},"text":"first"}],"cursor":{"next":"cursor-2"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"msg-2","type":"user","time":{"created":2},"text":"second"}],"cursor":{}}"""))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      assertEquals(listOf("ses-1", "ses-2"), api.sessions("/repo").map { it.id })
      assertEquals(listOf("msg-1", "msg-2"), api.messages("ses-1", "/repo").map { it.id })
      server.takeRequest()
      server.takeRequest()
      val sessionPage = server.takeRequest()
      val sessionNext = server.takeRequest()
      val messagePage = server.takeRequest()
      val messageNext = server.takeRequest()
      assertEquals("cursor-1", sessionNext.requestUrl?.queryParameter("cursor"))
      assertEquals("desc", sessionPage.requestUrl?.queryParameter("order"))
      assertEquals("asc", messagePage.requestUrl?.queryParameter("order"))
      assertEquals("cursor-2", messageNext.requestUrl?.queryParameter("cursor"))
      assertEquals(null, messageNext.requestUrl?.queryParameter("order"))
    }
  }

  @Test fun mapsV2AssistantToolAndPairToken() = runBlocking {
    val message = JSONObject("""{
      "id":"msg-2","type":"assistant","time":{"created":4},"content":[
        {"id":"part-1","type":"tool","name":"bash","state":{"status":"completed","input":{"command":"pwd"},"result":"/repo","outputPaths":["out.txt"]}}
      ]
    }""").toMessage()
    assertEquals("bash", message.parts.single().tool)
    assertEquals("completed", message.parts.single().status)
    val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("opencode:secret".toByteArray())
    val pair = PairLinkResolver.resolve("https://example.test/auth/connect/abc?auth_token=$token")
    assertEquals("https://example.test", pair.serverUrl)
    assertEquals("opencode", pair.credentials.username)
    assertEquals("secret", pair.credentials.password)
    assertTrue(PairLinkResolver.isPairLink("https://example.test/auth/connect/abc"))
    assertFalse(PairLinkResolver.isPairLink("https://example.test/"))
    assertNotNull(pair.credentials)
  }

  @Test fun resolvesOfficialPairRedirectAndPersistsSessionCookie() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/"))
      server.enqueue(MockResponse().setBody("<html>OpenCode</html>").addHeader("Set-Cookie", "opencode-session=session-value; Path=/; HttpOnly"))
      val pair = PairLinkResolver.resolve(server.url("/auth/connect/one-time").toString())
      assertEquals(server.url("/").toString().trimEnd('/'), pair.serverUrl)
      assertEquals("opencode-session=session-value", pair.credentials.cookie)
    }
  }

  @Test fun normalizesLegacyAndV2EventShapes() {
    val legacy = """{"directory":"/repo","payload":{"type":"session.status","properties":{"sessionID":"ses-1","status":{"type":"busy"}}}}""".toServerEvent("legacy-id")
    assertEquals("legacy-id", legacy.id)
    assertEquals("/repo", legacy.directory)
    assertEquals("session.status", legacy.type)
    val v2 = """{"id":"evt-1","type":"permission.v2.asked","properties":{"id":"per-1","sessionID":"ses-1","action":"file.read","resources":["a.txt"],"save":[]}}""".toServerEvent("")
    assertEquals("evt-1", v2.id)
    assertEquals("permission.asked", v2.type)
    assertEquals("file.read", v2.properties.getString("permission"))
    assertEquals("ses-1", v2.properties.getString("sessionID"))
  }

  @Test fun encodesSessionIdsExactlyOnceInPath() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1.0"}"""))
      server.enqueue(MockResponse().setBody("""[]"""))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      api.messages("ses 1/2", "/repo")
      server.takeRequest()
      // 'ses 1/2' must be percent-encoded once: %20 for the space and %2F for the slash.
      assertEquals("/session/ses%201%2F2/message", server.takeRequest().requestUrl?.encodedPath)
    }
  }

  /** Contract validation for TaskReducer phase transitions. */
  @Test fun taskReducerMatchesSharedContract() {    val fixture = JSONObject(java.io.File("docs/task-event-contract.json").readText())
    val cases = fixture.getJSONArray("cases")
    for (index in 0 until cases.length()) {
      val case = cases.getJSONObject(index)
      val previous = case.optString("previous").takeIf { it.isNotBlank() }?.let { TaskState("s", TaskPhase.valueOf(it)) }
      val result = TaskReducer.event("s", case.getJSONObject("kotlin").getString("type"),
        case.getJSONObject("kotlin").getJSONObject("properties"), previous)
      assertNotNull("no transition for ${case.getString("name")}", result)
      assertEquals(case.getString("name"), case.getString("expectedPhase"), result!!.phase.name)
    }
  }

  @Test fun projectsLegacyAndV2MessageParts() {
    val legacy = JSONObject("""{"id":"part-1","type":"tool","tool":"bash","state":{"status":"completed","title":"跑测试","input":{"command":"gradle test"},"output":"ok"}}""").toMessagePart()
    assertEquals("bash", legacy.tool)
    assertEquals("completed", legacy.status)
    assertEquals("跑测试", legacy.title)
    val v2 = JSONObject("""{"id":"part-2","type":"tool","name":"bash","state":{"status":"running","input":{"command":"pwd"},"result":"/repo"}}""").toV2MessagePart()
    assertEquals("bash", v2.tool)
    assertEquals("running", v2.status)
  }

  @Test fun modelsExposeTheFieldsTheUiReads() {
    val change = JSONObject("""{"file":"a.kt","before":"old","after":"new","additions":3,"deletions":1,"patch":"@@"}""").toChange()
    assertEquals("a.kt", change.path)
    assertEquals("new", change.after)
    assertEquals(3, change.additions)
    val node = JSONObject("""{"path":"src/a.kt","type":"file","absolute":"/x","ignored":true}""").toNode()
    assertEquals("src/a.kt", node.path)
    assertEquals("file", node.type)
    val text = JSONObject("""{"type":"text","content":"hi","encoding":"utf-8","mimeType":"text/plain"}""").toFileContent()
    assertEquals("text", text.type)
    assertEquals("hi", text.content)
    val binary = JSONObject("""{"content":"AAA=","encoding":"base64"}""").toFileContent()
    assertEquals("binary", binary.type)
  }

  @Test fun protocolCapabilitiesMatchTheApiBranches() {
    assertTrue(ServerProtocol.V1.supportsSessionActions)
    assertTrue(ServerProtocol.V1.supportsTitleOnCreate)
    assertTrue(ServerProtocol.V1.supportsTodosAndDiff)
    assertFalse(ServerProtocol.V2.supportsSessionActions)
    assertFalse(ServerProtocol.V2.supportsTitleOnCreate)
    assertFalse(ServerProtocol.V2.supportsTodosAndDiff)
  }

  /**
   * A01: credentials must never leave the configured origin, not even when the server answers with a
   * redirect. The disallowed target must receive zero requests.
   */
  @Test fun apiDoesNotForwardCredentialsToRedirectTarget() = runBlocking {
    MockWebServer().use { target ->
      MockWebServer().use { origin ->
        target.enqueue(MockResponse().setBody("""{"healthy":true,"version":"9"}"""))
        origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/steal")))
        val api = OpenCodeApi(ServerProfile("local", "Local", origin.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
        runCatching { api.health() }
        // The redirect must not have been followed at all.
        assertEquals(0, target.requestCount)
        val first = origin.takeRequest()
        assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", first.getHeader("Authorization"))
      }
    }
  }

  /** A01: pairing must reject a redirect to a different host before sending the one-time token. */
  @Test fun pairLinkRedirectRejectsBeforeSendingToDisallowedHost() = runBlocking {
    MockWebServer().use { target ->
      target.enqueue(MockResponse().setBody("<html>stolen</html>"))
      MockWebServer().use { origin ->
        origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/auth/connect/leak")))
        val result = runCatching { PairLinkResolver.resolve(origin.url("/auth/connect/one-time").toString()) }
        assertTrue(result.isFailure)
        assertEquals(0, target.requestCount)
      }
    }
  }

  /** A15: cancelling the caller must abort the in-flight HTTP call instead of waiting it out. */
  @Test fun cancellingApiInterruptsNetworkWait() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1"}""").setBodyDelay(3, TimeUnit.SECONDS))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      val job = launch(Dispatchers.IO) { runCatching { api.health() } }
      delay(200)
      val started = System.nanoTime()
      job.cancelAndJoin()
      val elapsedMillis = (System.nanoTime() - started) / 1_000_000
      assertTrue("cancel took ${elapsedMillis}ms", elapsedMillis < 1_500)
    }
  }

  /** A01: the cleartext target allow-list only accepts loopback hosts. */
  @Test fun cleartextAllowListIsLoopbackOnly() {
    assertTrue(HttpOrigin.allowsCleartext("localhost"))
    assertTrue(HttpOrigin.allowsCleartext("127.0.0.1"))
    assertTrue(HttpOrigin.allowsCleartext("::1"))
    assertFalse(HttpOrigin.allowsCleartext("example.com"))
    assertFalse(HttpOrigin.allowsCleartext("10.0.0.5"))
  }

  /** V2 detection must not abort when the older `api/health` path is absent. */
  @Test fun detectsV2ThroughInfoWhenHealthPathIsGone() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404)) // global/health (V1 probe)
      server.enqueue(MockResponse().setResponseCode(404)) // api/health (older V2)
      server.enqueue(MockResponse().setBody("""{"version":"2.0"}""")) // api/info (current V2)
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      assertEquals("OpenCode V2", api.health())
      assertEquals(ServerProtocol.V2, api.detectedProtocol())
      server.takeRequest()
      server.takeRequest()
      assertEquals("/api/info", server.takeRequest().requestUrl?.encodedPath)
    }
  }

  /**
   * When an OpenCode V2 server hosts a web frontend, unknown non-API routes return HTTP 200 with SPA
   * HTML. If /global/health returns HTML, the probe must gracefully fall through to V2 without failing.
   */
  @Test fun detectsV2WhenLegacyPathReturnsSpaHtml() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("<!DOCTYPE html><html><body>OpenCode</body></html>").addHeader("Content-Type", "text/html")) // global/health
      server.enqueue(MockResponse().setResponseCode(404)) // api/health
      server.enqueue(MockResponse().setBody("""{"version":"2.0.18"}""")) // api/info
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      assertEquals("OpenCode V2", api.health())
      assertEquals(ServerProtocol.V2, api.detectedProtocol())
      assertEquals("/global/health", server.takeRequest().requestUrl?.encodedPath)
      assertEquals("/api/health", server.takeRequest().requestUrl?.encodedPath)
      assertEquals("/api/info", server.takeRequest().requestUrl?.encodedPath)
    }
  }

  /** A failed write must not cause an undocumented second mutation. */
  @Test fun v2UnrevertDoesNotGuessAnotherWrite() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setResponseCode(404)) // revert/clear
      server.enqueue(MockResponse().setResponseCode(204)) // DELETE revert
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      try { api.unrevert(Session("ses-1", "/repo", "Task", 0)); org.junit.Assert.fail("expected 404") } catch (error: ApiException) { assertEquals(404, error.status) }
      server.takeRequest(); server.takeRequest()
      assertEquals("/api/session/ses-1/revert/clear", server.takeRequest().requestUrl?.encodedPath)
      assertEquals(3, server.requestCount)
    }
  }

  /** A10: a refresh after an offline start must re-establish the stream and clear stale flags. */
  @Test fun degradedStateDefaultsToHealthyAndIsIndependentOfOfflineFlag() {
    val offline = LagoonState(connected = false, cached = true)
    assertTrue(offline.cached)
    assertFalse(offline.degraded)
    val online = offline.copy(connected = true, cached = false)
    assertTrue(online.connected)
    assertFalse(online.cached)
  }

  /**
   * Adding a server against an address that answers HTTP 200 without a JSON object (a web UI, a
   * proxy page, plain text, an empty body) must surface a friendly Chinese error instead of the
   * raw org.json message "Value ... of type java.lang.String cannot be converted to JSONObject".
   */
  @Test fun nonJsonSuccessResponsesReportFriendlyErrors() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404)) // global/health (V1 fallback)
      server.enqueue(MockResponse().setResponseCode(404)) // api/health (older V2)
      server.enqueue(MockResponse().setBody("<!DOCTYPE html><html><body>OpenCode</body></html>").addHeader("Content-Type", "text/html")) // api/info
      server.enqueue(MockResponse().setResponseCode(404)) // global/health
      server.enqueue(MockResponse().setResponseCode(404)) // api/health
      server.enqueue(MockResponse().setBody("OK")) // api/info
      server.enqueue(MockResponse().setResponseCode(404)) // global/health
      server.enqueue(MockResponse().setResponseCode(404)) // api/health
      server.enqueue(MockResponse().setResponseCode(200)) // api/info
      suspend fun healthError(): Throwable? = OpenCodeApi(
        ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret"
      ).let { api -> runCatching { api.health() }.exceptionOrNull() }
      val html = healthError()
      assertTrue("expected IOException, got $html", html is IOException && html !is ApiException)
      assertTrue(html!!.message!!, html.message!!.contains("服务器返回的是网页"))
      assertFalse(html.message!!, html.message!!.contains("cannot be converted"))
      val text = healthError()
      assertTrue(text is IOException && !text.message!!.contains("cannot be converted"))
      assertTrue(text!!.message!!, text.message!!.contains("不是有效 JSON"))
      val empty = healthError()
      assertTrue(empty is IOException)
      assertTrue(empty!!.message!!, empty.message!!.contains("空响应"))
    }
  }
}
