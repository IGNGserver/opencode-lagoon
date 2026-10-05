package com.igng.opencode.lagoon.core

import java.time.Instant
import java.time.ZoneId

/** How the home list is split into sections (the ⋯ menu's 分组方式). */
enum class HomeGrouping(val label: String) { DATE("日期"), PROJECT("项目"), STATUS("状态") }

data class HomeSection(val key: String, val title: String, val sessions: List<Session>)

/**
 * Sections of the home list. Input sessions keep their activity order inside every section; grouping
 * never reorders them (a waiting or running session only changes its icon, see docs/HOME_REDESIGN.md).
 * Pinned sessions are this device's choice and are lifted into a leading 置顶 section.
 */
object HomeSections {
  fun build(
    grouping: HomeGrouping,
    sessions: List<Session>,
    projects: List<Project>,
    statuses: Map<String, SessionStatus>,
    pinned: Set<String> = emptySet(),
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
  ): List<HomeSection> {
    val (top, rest) = sessions.partition { it.id in pinned }
    val sections = when (grouping) {
      HomeGrouping.DATE -> byDate(rest, now, zone)
      HomeGrouping.PROJECT -> byProject(rest, projects)
      HomeGrouping.STATUS -> byStatus(rest, statuses)
    }
    return listOfNotNull(top.takeIf { it.isNotEmpty() }?.let { HomeSection("pinned", "置顶", it) }) + sections
  }

  /** 今天 / 昨天 / 近 7 天 / 近 30 天 / 更早, by local calendar day of the last activity. */
  fun byDate(sessions: List<Session>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<HomeSection> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun bucket(session: Session): Pair<String, String> {
      val day = Instant.ofEpochMilli(session.activityAt).atZone(zone).toLocalDate()
      return when {
        !day.isBefore(today) -> "today" to "今天"
        day == today.minusDays(1) -> "yesterday" to "昨天"
        day.isAfter(today.minusDays(7)) -> "week" to "近 7 天"
        day.isAfter(today.minusDays(30)) -> "month" to "近 30 天"
        else -> "earlier" to "更早"
      }
    }
    return sessions.groupBy(::bucket).map { (key, list) -> HomeSection("date:${key.first}", key.second, list) }
  }

  /** One section per project, ordered by its latest session; sessions outside known projects group by folder. */
  fun byProject(sessions: List<Session>, projects: List<Project>): List<HomeSection> =
    sessions.groupBy { session ->
      val project = resolveSessionProject(session, projects)
      (project?.id ?: "dir:${normalizedDirectory(session.directory)}") to
        (project?.name ?: session.directory.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { session.directory })
    }.map { (key, list) -> HomeSection("project:${key.first}", key.second, list) }

  /** 需要处理 › 运行中 › 未读结果 › 其他, the order the official client ranks session indicators. */
  fun byStatus(sessions: List<Session>, statuses: Map<String, SessionStatus>): List<HomeSection> {
    fun bucket(session: Session) = when (statuses[session.id] ?: SessionStatus.NONE) {
      SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION -> 0
      SessionStatus.RUNNING -> 1
      SessionStatus.FAILED, SessionStatus.COMPLETED -> 2
      SessionStatus.NONE -> 3
    }
    val titles = listOf("需要处理", "运行中", "未读结果", "其他")
    return sessions.groupBy(::bucket).toSortedMap().map { (index, list) -> HomeSection("status:$index", titles[index], list) }
  }
}

