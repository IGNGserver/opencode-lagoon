package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「全部会话」是默认范围；范围只是首页过滤，不决定新会话建在哪。 */
class HomeScopeTest {
  private val app = Project("p-app", "/home/me/项目/app", "app")
  private val web = Project("p-web", "/home/me/项目/web", "web")
  private val empty = Project("directory:/home/me/notes", "/home/me/notes", "notes")
  private val projects = listOf(app, web, empty)
  private val sessions = listOf(
    Session("a1", "/home/me/项目/app", "A1", updated = 10),
    Session("w1", "/home/me/项目/web/.wt/x", "W1", updated = 30),
    Session("w2", "/home/me/项目/web", "W2", updated = 20),
    Session("child", "/home/me/项目/web", "C", updated = 40, parentId = "w1"),
    Session("old", "/home/me/项目/app", "Archived", updated = 50, archived = true)
  )

  @Test fun allScopeKeepsEveryRootAndProjectScopeFilters() {
    assertEquals(listOf("w1", "w2", "a1"), HomeScope.roots(sessions, projects, null).map { it.id })
    assertEquals(listOf("w1", "w2"), HomeScope.roots(sessions, projects, "p-web").map { it.id })
    assertEquals(emptyList<String>(), HomeScope.roots(sessions, projects, empty.id).map { it.id })
    assertEquals(mapOf("p-app" to 1, "p-web" to 2), HomeScope.counts(sessions, projects))
  }

  @Test fun draftTargetFollowsScopeElseLatestActivity() {
    assertEquals("p-app", HomeScope.draftTarget(sessions, projects, "p-app", "p-web"))
    assertEquals("p-web", HomeScope.draftTarget(sessions, projects, null, "p-app"))
    assertEquals("notes-only", HomeScope.draftTarget(emptyList(), listOf(Project("notes-only", "/n", "n")), null, null))
    assertEquals(listOf("p-web", "p-app", empty.id), HomeScope.byActivity(sessions, projects).map { it.id })
  }

  @Test fun pathHelpersForTheFolderBrowser() {
    assertEquals("~/项目/app", HomeScope.displayPath("/home/me/项目/app", "/home/me"))
    assertEquals("~", HomeScope.displayPath("/home/me", "/home/me/"))
    assertEquals("/srv/x", HomeScope.displayPath("/srv/x", "/home/me"))
    assertEquals(listOf("/" to "/", "home" to "/home", "me" to "/home/me"), HomeScope.crumbs("/home/me/"))
    assertEquals("/home", HomeScope.parent("/home/me"))
    assertEquals("/", HomeScope.parent("/home"))
    assertEquals("/home/me/app", HomeScope.child("/home/me/", "app"))
  }

  @Test fun v2BrowsesFoldersThroughTheRequestLocation() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
          "/global/health" -> MockResponse().setResponseCode(404)
          "/api/health" -> MockResponse().setBody("""{"healthy":true}""")
          "/api/location" -> MockResponse().setBody("""{"data":{"directory":"/home/me","project":{"id":"global"}}}""")
          "/api/fs/list" -> MockResponse().setBody("""{"data":[{"path":"项目","type":"directory"},{"path":".config/","type":"directory"},{"path":"a.txt","type":"file"},{"path":"Apps","type":"directory"}]}""")
          else -> MockResponse().setResponseCode(404)
        }
      }
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      assertEquals("/home/me", api.browseRoot())
      assertEquals(listOf("Apps", "项目", ".config"), api.listDirectories("/home/me"))
      val requests = List(server.requestCount) { server.takeRequest() }
      val list = requests.single { it.requestUrl!!.encodedPath == "/api/fs/list" }
      assertEquals("/home/me", list.requestUrl!!.queryParameter("location[directory]"))
      assertEquals(null, list.requestUrl!!.queryParameter("path"))
    }
  }

  @Test fun v1BrowsesFoldersAsTheInstanceDirectory() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
          "/global/health" -> MockResponse().setBody("""{"healthy":true,"version":"1.0"}""")
          "/path" -> MockResponse().setBody("""{"home":"/root","directory":"/srv"}""")
          "/file" -> MockResponse().setBody("""[{"name":"src","path":"src","type":"directory"},{"name":"README.md","path":"README.md","type":"file"}]""")
          else -> MockResponse().setResponseCode(404)
        }
      }
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      assertEquals("/root", api.browseRoot())
      assertEquals(listOf("src"), api.listDirectories("/srv/repo"))
      val file = List(server.requestCount) { server.takeRequest() }.single { it.requestUrl!!.encodedPath == "/file" }
      assertEquals("/srv/repo", file.requestUrl!!.queryParameter("directory"))
    }
  }
}
