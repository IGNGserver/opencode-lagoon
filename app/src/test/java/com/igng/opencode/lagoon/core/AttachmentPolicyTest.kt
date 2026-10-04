package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Phone files travel inline as data: URIs; only types OpenCode passes to the model are accepted. */
class AttachmentPolicyTest {
  @Test fun acceptsImagesAndTextButRejectsBinariesTheModelCannotRead() {
    assertEquals(AttachmentPolicy.Kind.IMAGE, AttachmentPolicy.kind("image/heic", "IMG_1.heic"))
    assertEquals(AttachmentPolicy.Kind.TEXT, AttachmentPolicy.kind("application/octet-stream", "Main.kt"))
    assertEquals(AttachmentPolicy.Kind.TEXT, AttachmentPolicy.kind("image/svg+xml", "logo.svg"))
    assertEquals(AttachmentPolicy.Kind.TEXT, AttachmentPolicy.kind("text/csv", "data"))
    assertNull(AttachmentPolicy.kind("application/pdf", "spec.pdf"))
    assertNull(AttachmentPolicy.kind("video/mp4", "clip.mp4"))
    assertNotNull(AttachmentPolicy.rejection("application/pdf", "spec.pdf", 10, 0))
  }

  @Test fun enforcesThePerMessageBudget() {
    val mb = 1024L * 1024
    assertNull(AttachmentPolicy.rejection("image/png", "a.png", 12 * mb, 0))
    assertNotNull(AttachmentPolicy.rejection("image/png", "b.png", 12 * mb, 12 * mb))
    assertNotNull(AttachmentPolicy.rejection("text/plain", "huge.log", 21 * mb, 0))
  }

  @Test fun buildsDataUris() {
    assertEquals("data:text/plain;base64,SGk=", AttachmentPolicy.dataUri("text/plain; charset=utf-8", "Hi".toByteArray()))
    assertEquals("text/plain", AttachmentPolicy.wireMime(AttachmentPolicy.Kind.TEXT, "application/json"))
    assertEquals("image/webp", AttachmentPolicy.wireMime(AttachmentPolicy.Kind.IMAGE, "image/webp"))
  }

  @Test fun v1SendsInlineFilesAsFileParts() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1.0"}"""))
      server.enqueue(MockResponse().setResponseCode(204))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      api.send(Session("s", "/repo", "Task", 0), "看图", null, null, inline = listOf(InlineFile("a.jpg", "image/jpeg", "data:image/jpeg;base64,AAA=")))
      server.takeRequest()
      val parts = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("parts")
      assertEquals("看图", parts.getJSONObject(0).getString("text"))
      val file = parts.getJSONObject(1)
      assertEquals("file", file.getString("type"))
      assertEquals("data:image/jpeg;base64,AAA=", file.getString("url"))
      assertEquals("a.jpg", file.getString("filename"))
      assertEquals("image/jpeg", file.getString("mime"))
    }
  }

  @Test fun v2SendsInlineFilesInPromptFilesUsingTheDocumentedUriField() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setBody("""{"paths":{"/api/session/{sessionID}/prompt":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"prompt":{"type":"object","properties":{"text":{"type":"string"},"files":{"type":"array","items":{"type":"object","properties":{"uri":{"type":"string"},"name":{"type":"string"}}}}}}}}}}}}}}}"""))
      server.enqueue(MockResponse().setResponseCode(204))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.discoverCapabilities()
      api.send(Session("s", "/repo", "Task", 0), "", null, null, inline = listOf(InlineFile("notes.txt", "text/plain", "data:text/plain;base64,SGk=")))
      repeat(3) { server.takeRequest() }
      val prompt = server.takeRequest()
      assertEquals("/api/session/s/prompt", prompt.requestUrl?.encodedPath)
      val file = JSONObject(prompt.body.readUtf8()).getJSONObject("prompt").getJSONArray("files").getJSONObject(0)
      assertEquals("data:text/plain;base64,SGk=", file.getString("uri"))
      assertEquals("notes.txt", file.getString("name"))
    }
  }

  @Test fun v2CatalogDropsDisabledModelsAndToleratesMissingProviderRoute() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"a","providerID":"newapi","name":"A","time":{"released":0},"status":"active","enabled":true},{"id":"b","providerID":"newapi","name":"B","enabled":false}]}"""))
      server.enqueue(MockResponse().setResponseCode(404))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      val catalog = api.modelCatalog("/repo")
      assertEquals(listOf("a"), catalog.map { it.choice.modelId })
      assertEquals("newapi", catalog.single().providerName)
      repeat(2) { server.takeRequest() }
      assertEquals("/repo", server.takeRequest().requestUrl?.queryParameter("location[directory]"))
      assertEquals("/api/provider", server.takeRequest().requestUrl?.encodedPath)
    }
  }
}
