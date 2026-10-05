package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiPagingTest {
  private fun api(server:MockWebServer)=OpenCodeApi(ServerProfile("server","Server",server.url("/").toString(),allowCleartext=true), "fixture")
  @Test fun blankTitleIsOmittedSoTheServerCanNameTheSession()=runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"data":{"id":"s","location":{"directory":"/repo"},"time":{}}}"""))
      api(server).createSession("/repo", " ")
      val body=JSONObject(server.takeRequest().body.readUtf8())
      assertFalse(body.has("title"))
      assertEquals("/repo",body.getJSONObject("location").getString("directory"))
    }
  }
  @Test fun rootCatalogIsGlobalAndRetainsCursorEvenWhenPageContainsOnlyHiddenSessions() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"hidden","parentID":"parent","location":{"directory":"/tree"},"time":{}}],"cursor":{"next":"next"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"root","location":{"directory":"/other"},"time":{}}],"cursor":{}}"""))
      val client = api(server)
      val first = client.rootSessionsPage()
      assertTrue(groupSessions(first.items, emptyList()).isEmpty())
      assertEquals("next", first.next)
      assertEquals("root", client.rootSessionsPage(cursor = first.next).items.single().id)
      val requests = List(2) { server.takeRequest() }
      for (request in requests) {
        assertNull(request.requestUrl!!.queryParameter("directory"))
        assertEquals("null", request.requestUrl!!.queryParameter("parentID"))
      }
      assertEquals("desc", requests[0].requestUrl!!.queryParameter("order"))
      assertNull(requests[1].requestUrl!!.queryParameter("order"))
    }
  }
  @Test fun childrenFollowCursorsWithTheParentFilter() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"c1","parentID":"p","location":{"directory":"/repo"},"time":{}}],"cursor":{"next":"more"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"c2","parentID":"p","location":{"directory":"/repo"},"time":{}}],"cursor":{}}"""))
      assertEquals(listOf("c1", "c2"), api(server).children(Session("p", "/repo", "P", 0)).map { it.id })
      server.takeRequest()
      val next = server.takeRequest().requestUrl!!
      assertEquals("p", next.queryParameter("parentID")); assertEquals("more", next.queryParameter("cursor"))
    }
  }

  @Test fun documentedViewSendsExactIdleCycleAndDoesNotUseTheCurrentTime() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"paths":{"/api/session/{sessionID}/view":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"idle":{"type":"number"}},"required":["idle"]}}}}}}}}"""))
      server.enqueue(MockResponse().setResponseCode(204))
      val client = api(server); val capabilities = client.discoverCapabilities()
      assertNotNull(capabilities.sessionView)
      client.viewSession(Session("s", "/repo", "Title", 1, idle = 123), 123)
      val request = List(2) { server.takeRequest() }.last()
      assertEquals("POST", request.method)
      assertEquals("/api/session/s/view", request.requestUrl!!.encodedPath)
      assertEquals(123L, JSONObject(request.body.readUtf8()).getLong("idle"))
    }
  }
}
