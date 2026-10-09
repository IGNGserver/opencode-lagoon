package com.igng.opencode.lagoon.core

/**
 * Mirrors the official per-server notification ledger (`packages/app/src/context/notification.tsx`):
 * completion/failure results of root sessions, 500 entries, 30 days. An entry is created only when this
 * device witnesses a run end; opening the session marks all of its entries viewed.
 */
data class SessionNotice(val id: String, val sessionId: String, val time: Long, val error: Boolean, val viewed: Boolean = false)

/** Live-event source for legacy execution events and native V2 step settlement. */
internal fun ServerEvent.resultNotice(session: Session, failureOverride: Boolean? = null, now: Long = System.currentTimeMillis()): SessionNotice? {
  if (session.parentId != null) return null
  val terminalV2Step = type == "session.next.step.ended" && properties.str("finish") !in setOf("", "tool-calls", "tool_use", "function_call", "continue")
  val terminal = type in setOf("session.execution.failed", "session.execution.succeeded", "session.idle", "session.next.step.failed") ||
    type == "session.status" && properties.obj("status").str("type") == "idle" || terminalV2Step
  if (!terminal) return null
  val failure = failureOverride ?: (
    type in setOf("session.execution.failed", "session.next.step.failed") ||
      type == "session.next.step.ended" && properties.str("finish") in setOf("error", "failed", "content-filter")
    )
  val eventTime = created.takeIf { it > 0 } ?: now
  return SessionNotice(id.ifBlank { "$type:${session.id}:$eventTime" }, session.id, eventTime, failure)
}

/**
 * Mobile compensation for the volatile event stream: this device saw the root's family running (since
 * [runSince]) and an authoritative read now shows it idle. Native V2 does not put an outcome on
 * `Session.Info`, so reconciliation treats an unqualified settlement as successful; explicit failure
 * events are recorded by the live path.
 */
internal fun Session.transitionNotice(runSince: Long, now: Long = System.currentTimeMillis()): SessionNotice? {
  if (parentId != null || outcome == "interrupted") return null
  return SessionNotice("run:$id:$runSince", id, now, outcome == "failed")
}

/** True when a notice for the same run (any source) is already in the ledger. */
internal fun List<SessionNotice>.coversRun(session: String, runSince: Long): Boolean =
  any { it.sessionId == session && it.time >= runSince }

internal fun List<SessionNotice>.unseenFor(session: String): List<SessionNotice> = filter { it.sessionId == session && !it.viewed }

internal fun List<SessionNotice>.pruned(now: Long): List<SessionNotice> =
  filter { it.time >= now - 30L * 24 * 60 * 60 * 1000 }.sortedBy { it.time }.takeLast(500)

/** Marks the given entries of one session viewed. */
internal fun List<SessionNotice>.viewObserved(session: String, ids: Set<String>): List<SessionNotice> =
  map { if (it.sessionId == session && it.id in ids) it.copy(viewed = true) else it }
