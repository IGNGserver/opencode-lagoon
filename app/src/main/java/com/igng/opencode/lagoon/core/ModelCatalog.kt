package com.igng.opencode.lagoon.core

import org.json.JSONObject

/**
 * One selectable model with the catalog metadata needed to decide its default visibility.
 * [released] is epoch milliseconds; 0 means the catalog carries no release date (custom models).
 */
data class ModelInfo(
  val choice: ModelChoice,
  val providerName: String = choice.providerId,
  val family: String? = null,
  val released: Long = 0,
  /** Whether the model accepts image input; null when the catalog does not say. */
  val imageInput: Boolean? = null,
  val deprecated: Boolean = false
) {
  val key: String get() = modelKey(choice)
}

/** Provider IDs cannot contain `/`, so `provider/model` is unambiguous even for model IDs with `/` or `:`. */
fun modelKey(model: ModelChoice): String = "${model.providerId}/${model.modelId}"

/**
 * Mirrors the official OpenCode client's model selector (packages/app `context/models.tsx`).
 * The desktop "管理模型" switches live in that client's local storage, not on the server, so the phone
 * applies the same default rule and keeps its own per-server switches:
 * - an explicit user switch always wins;
 * - otherwise a model is shown when it is the newest of its provider + family released within six months;
 * - models without a release date (custom/self-hosted entries) are shown;
 * - everything else, including models without a family, starts hidden.
 */
object ModelVisibility {
  /** luxon's `diffNow().as("months")` uses 30-day months; six of them. */
  private const val SIX_MONTHS_MILLIS = 6L * 30 * 24 * 60 * 60 * 1000

  fun latest(models: List<ModelInfo>, now: Long = System.currentTimeMillis()): Set<String> =
    models.asSequence()
      .filter { it.released > 0 && kotlin.math.abs(now - it.released) < SIX_MONTHS_MILLIS }
      .filter { !it.family.isNullOrBlank() }
      .groupBy { it.choice.providerId to it.family }
      .values.mapNotNull { group -> group.maxByOrNull { it.released }?.key }
      .toSet()

  fun isVisible(model: ModelInfo, latest: Set<String>, overrides: Map<String, Boolean>): Boolean {
    overrides[model.key]?.let { return it }
    if (model.deprecated) return false
    if (model.key in latest) return true
    return model.released <= 0
  }

  fun visible(models: List<ModelInfo>, overrides: Map<String, Boolean>, now: Long = System.currentTimeMillis()): List<ModelInfo> {
    val latest = latest(models, now)
    return models.filter { isVisible(it, latest, overrides) }
  }
}

/** V2 `/api/model` item. Disabled entries are not offered at all, matching the server's catalog rules. */
internal fun JSONObject.toV2ModelInfo(providerNames: Map<String, String>): ModelInfo? {
  if (has("enabled") && !optBoolean("enabled", true)) return null
  val provider = str("providerID")
  val id = str("id")
  if (provider.isBlank() || id.isBlank()) return null
  val inputs = obj("capabilities").arr("input")
  return ModelInfo(
    ModelChoice(provider, id, str("name").ifBlank { id }),
    providerName = providerNames[provider].orEmpty().ifBlank { provider },
    family = str("family").ifBlank { null },
    released = epochMillis(obj("time").optDouble("released", 0.0)),
    imageInput = if (obj("capabilities").has("input")) (0 until inputs.length()).any { inputs.optString(it) == "image" } else null,
    deprecated = str("status") == "deprecated"
  )
}

/** V1 `config/providers` model entry (models.dev shape). */
internal fun JSONObject.toV1ModelInfo(providerId: String, providerName: String, key: String): ModelInfo {
  val inputs = obj("modalities").arr("input")
  return ModelInfo(
    ModelChoice(providerId, key, str("name").ifBlank { key }),
    providerName = providerName.ifBlank { providerId },
    family = str("family").ifBlank { null },
    released = parseReleaseDate(str("release_date")),
    imageInput = when {
      obj("modalities").has("input") -> (0 until inputs.length()).any { inputs.optString(it) == "image" }
      has("attachment") -> optBoolean("attachment")
      else -> null
    },
    deprecated = str("status") == "deprecated"
  )
}

/** Accepts epoch seconds or milliseconds; anything non-positive means "no release date". */
internal fun epochMillis(value: Double): Long = when {
  value.isNaN() || value <= 0 -> 0
  value < 100_000_000_000.0 -> (value * 1000).toLong()
  else -> value.toLong()
}

/** `YYYY-MM-DD` or `YYYY-MM` from models.dev; invalid or missing dates count as unknown. */
internal fun parseReleaseDate(value: String): Long {
  val match = Regex("^(\\d{4})-(\\d{2})(?:-(\\d{2}))?").find(value.trim()) ?: return 0
  val (year, month, day) = match.destructured
  return runCatching {
    java.time.LocalDate.of(year.toInt(), month.toInt(), day.ifBlank { "1" }.toInt())
      .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
  }.getOrDefault(0)
}
