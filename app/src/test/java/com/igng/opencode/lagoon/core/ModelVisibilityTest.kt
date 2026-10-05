package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the official client rule the phone applies when no switch was made on this phone. */
class ModelVisibilityTest {
  private val day = 24L * 60 * 60 * 1000
  private val now = 1_790_000_000_000L
  private fun model(provider: String, id: String, family: String?, ageDays: Long?, deprecated: Boolean = false) =
    ModelInfo(ModelChoice(provider, id, id), family = family, released = ageDays?.let { now - it * day } ?: 0, deprecated = deprecated)

  @Test fun showsOnlyTheNewestOfEachRecentFamily() {
    val models = listOf(
      model("openai", "gpt-5.2", "gpt", 20), model("openai", "gpt-5.1", "gpt", 90),
      model("openai", "o5", "o", 10),
      model("anthropic", "sonnet-5", "claude-sonnet", 30), model("anthropic", "old", "claude-old", 400)
    )
    assertEquals(listOf("gpt-5.2", "o5", "sonnet-5"), ModelVisibility.visible(models, emptyMap(), now).map { it.choice.modelId })
  }

  @Test fun customModelsWithoutReleaseDateStayVisibleButUndatedFamilyLessDoNot() {
    val custom = model("newapi", "gemini-3.8-flash", null, null)
    val familyLess = model("zen", "space-bunny", null, 5)
    val visible = ModelVisibility.visible(listOf(custom, familyLess), emptyMap(), now)
    assertEquals(listOf("gemini-3.8-flash"), visible.map { it.choice.modelId })
  }

  @Test fun phoneSwitchesWinOverTheDefaultRule() {
    val custom = model("ubuntu", "qwen", null, null)
    val old = model("openai", "gpt-4", "gpt-4", 900)
    val deprecated = model("openai", "legacy", "legacy", 10, deprecated = true)
    val overrides = mapOf(custom.key to false, old.key to true)
    val visible = ModelVisibility.visible(listOf(custom, old, deprecated), overrides, now).map { it.choice.modelId }
    assertEquals(listOf("gpt-4"), visible)
    assertTrue(ModelVisibility.isVisible(deprecated, emptySet(), mapOf(deprecated.key to true)))
  }

  @Test fun keyIsUnambiguousForModelIdsWithSlashesAndColons() {
    assertEquals("openrouter/anthropic/claude-sonnet-4.5", modelKey(ModelChoice("openrouter", "anthropic/claude-sonnet-4.5", "x")))
    assertEquals("ollama/gemma3:4b", modelKey(ModelChoice("ollama", "gemma3:4b", "x")))
  }

  @Test fun parsesV2CatalogEntries() {
    val item = JSONObject("""{"id":"gpt-5.2","providerID":"openai","family":"gpt","name":"GPT-5.2","capabilities":{"tools":true,"input":["text","image"],"output":["text"]},"time":{"released":1767225600000},"status":"active","enabled":true}""")
    val info = item.toV2ModelInfo(mapOf("openai" to "OpenAI"))!!
    assertEquals("OpenAI", info.providerName)
    assertEquals("gpt", info.family)
    assertEquals(1767225600000L, info.released)
    assertEquals(true, info.imageInput)
    assertNull(JSONObject("""{"id":"x","providerID":"p","name":"X","enabled":false}""").toV2ModelInfo(emptyMap()))
    val custom = JSONObject("""{"id":"m","providerID":"newapi","name":"M","time":{"released":0},"status":"active","enabled":true}""").toV2ModelInfo(emptyMap())!!
    assertEquals(0L, custom.released)
    assertEquals("newapi", custom.providerName)
    assertNull(custom.imageInput)
  }

  @Test fun parsesV2CatalogDatesAndDeprecation() {
    val info = JSONObject("""{"id":"claude-x","providerID":"anthropic","name":"Claude","family":"claude-sonnet","time":{"released":1767225600},"capabilities":{"input":["text"]},"status":"deprecated"}""")
      .toV2ModelInfo(mapOf("anthropic" to "Anthropic"))!!
    assertEquals("claude-x", info.choice.modelId)
    assertEquals(false, info.imageInput)
    assertTrue(info.deprecated)
    assertEquals(1_767_225_600_000L, info.released)
    assertEquals(1_767_225_600_000L, epochMillis(1_767_225_600.0))
    assertEquals(0L, epochMillis(0.0))
    assertFalse(ModelVisibility.latest(listOf(info), info.released + day).isEmpty())
  }
}
