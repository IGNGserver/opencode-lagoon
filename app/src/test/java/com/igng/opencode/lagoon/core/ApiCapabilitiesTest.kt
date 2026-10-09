package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiCapabilitiesTest {
  @Test fun currentRoutesAreReadFromTheDocument() {
    val document = JSONObject("""{"paths":{
      "/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":{"schema":{"${'$'}ref":"#/components/schemas/Update"}}}}}},
      "/api/session/{sessionID}/revert":{"delete":{}},
      "/api/session/{sessionID}/diff":{"get":{}},
      "/api/session/{sessionID}/view":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"idle":{"type":"number"}},"required":["idle"]}}}}}},
      "/api/permission/saved":{"get":{}}
    },"components":{"schemas":{"Update":{"type":"object","properties":{"title":{"type":"string"},"metadata":{"type":"object"}}}}}}""")
    val caps = ApiCapabilities.fromDocument(document)
    assertEquals(ActionEndpoint("PATCH", "api/session/{sessionID}"), caps.actions[SessionAction.RENAME])
    assertEquals(ActionEndpoint("DELETE", "api/session/{sessionID}/revert"), caps.actions[SessionAction.UNREVERT])
    assertTrue(caps.diff); assertEquals(ActionEndpoint("GET", "api/session/{sessionID}/diff"), caps.diffEndpoint)
    assertNotNull(caps.sessionView); assertTrue(caps.documented)
  }
  @Test fun unknownRequiredWriteFieldsKeepTheActionUnavailable() {
    val doc = JSONObject("""{"paths":{"/api/session/{id}/rename":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"title":{"type":"string"}},"required":["title","unknown"]}}}}}}}}""")
    assertFalse(ApiCapabilities.fromDocument(doc).supports(SessionAction.RENAME))
  }
  @Test fun archiveIsOfferedOnlyWhenTheUpdateBodyDocumentsTimeArchived() {
    fun caps(time: String) = ApiCapabilities.fromDocument(JSONObject("""{"paths":{"/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":{"schema":
      {"type":"object","properties":{"title":{"type":"string"},"time":$time}}}}}}}}}"""))
    assertNull(ApiCapabilities.BASELINE.archive)
    assertNull(caps("""{"type":"object","properties":{"updated":{"type":"number"}}}""").archive)
    assertEquals(ArchiveEndpoint(ActionEndpoint("PATCH", "api/session/{sessionID}"), restorable = false),
      caps("""{"type":"object","properties":{"archived":{"type":"number"}}}""").archive)
    assertTrue(caps("""{"type":"object","properties":{"archived":{"anyOf":[{"type":"number"},{"type":"null"}]}}}""").archive!!.restorable)
  }
  @Test fun aNullFirstUnionStillResolvesToItsSchema() {
    val doc = JSONObject("""{"paths":{"/api/session/{id}":{"patch":{"requestBody":{"content":{"application/json":{"schema":
      {"anyOf":[{"type":"null"},{"type":"object","properties":{"title":{"type":"string"}}}]}}}}}}}}""")
    assertTrue(ApiCapabilities.fromDocument(doc).supports(SessionAction.RENAME))
  }
  @Test fun aDocumentWithoutPathsMeansTheCurrentContract() {
    assertEquals(ApiCapabilities.BASELINE, ApiCapabilities.fromDocument(JSONObject("{}")))
    assertEquals(ApiCapabilities.BASELINE, ApiCapabilities.fromDocument(JSONObject("""{"paths":{}}""")))
  }

  @Test fun aCombinedDocumentPrefersNativeCollectionsButKeepsDocumentedLegacySessionActions() {
    val document = JSONObject("""{"paths":{
      "/session":{"get":{}},
      "/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"title":{"type":"string"}}}}}}},"delete":{}},
      "/session/{sessionID}/fork":{"post":{}},
      "/api/health":{"get":{}},
      "/api/session":{"get":{}},
      "/api/session/{sessionID}":{"get":{}},
      "/api/session/{sessionID}/revert/clear":{"post":{}},
      "/api/event":{"get":{}}
    }}""")
    val caps = ApiCapabilities.fromDocument(document)
    assertTrue(caps.nativeV2)
    assertEquals("api/session", caps.sessionRoot)
    assertEquals("api/event", caps.eventPath)
    assertEquals(ActionEndpoint("PATCH", "session/{sessionID}"), caps.actions[SessionAction.RENAME])
    assertEquals(ActionEndpoint("DELETE", "session/{sessionID}"), caps.removeEndpoint)
    assertEquals(ActionEndpoint("POST", "session/{sessionID}/fork"), caps.forkEndpoint)
    assertEquals(ActionEndpoint("POST", "api/session/{sessionID}/revert/clear"), caps.actions[SessionAction.UNREVERT])
  }
}
