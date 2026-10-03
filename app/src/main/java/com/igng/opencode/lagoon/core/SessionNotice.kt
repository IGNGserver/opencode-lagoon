package com.igng.opencode.lagoon.core

/** Mirrors the official per-server notification ledger: results/errors, 500 entries, 30 days. */
data class SessionNotice(val id: String, val sessionId: String, val time: Long, val error: Boolean, val viewed: Boolean = false)

internal fun ServerEvent.resultNotice(session: Session, now: Long = System.currentTimeMillis()): SessionNotice? {
  if (session.parentId != null) return null
  val failure = type in setOf("session.error", "session.execution.failed")
  if (!failure && type !in setOf("session.idle", "session.execution.succeeded")) return null
  val time = properties.optLong("timestamp").takeIf { it > 0 } ?: now
  return SessionNotice(id.ifBlank { "$type:${session.id}:$time" }, session.id, time, failure)
}

internal fun List<SessionNotice>.pruned(now: Long): List<SessionNotice> =
  filter { it.time >= now - 30L * 24 * 60 * 60 * 1000 }.sortedBy { it.time }.takeLast(500)

/** Only acknowledge the results captured before a successful transcript request. */
internal fun List<SessionNotice>.viewObserved(session: String, ids: Set<String>): List<SessionNotice> =
  map { if (it.sessionId == session && it.id in ids) it.copy(viewed = true) else it }
