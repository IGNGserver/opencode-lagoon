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
    assertTrue(caps.diff); assertTrue(caps.savedPermissions); assertNotNull(caps.sessionView); assertTrue(caps.documented)
  }
  @Test fun unknownRequiredWriteFieldsKeepTheActionUnavailable() {
    val doc = JSONObject("""{"paths":{"/api/session/{id}/rename":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"title":{"type":"string"}},"required":["title","unknown"]}}}}}}}}""")
    assertFalse(ApiCapabilities.fromDocument(doc).supports(SessionAction.RENAME))
  }
  @Test fun aDocumentWithoutPathsMeansTheCurrentContract() {
    assertEquals(ApiCapabilities.BASELINE, ApiCapabilities.fromDocument(JSONObject("{}")))
  }
}
