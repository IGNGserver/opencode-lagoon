package com.igng.opencode.lagoon.ui

/**
 * Top-level destinations shown in the bottom navigation / wide-screen rail.
 *
 * 信息架构主线：Server → Project/Directory → Session → Conversation。
 * 「会话」是默认首页（root），状态附着在 Session 上，不再保留工作台 Dashboard。
 */
internal enum class RootTab(val label: String, val isRoot: Boolean = false) {
  SESSIONS("会话", isRoot = true),
  ACTIVITY("活动"),
  SETTINGS("设置")
}

/** The predictive back transition type for in-app navigation. */
internal enum class BackStage {
  NONE,
  DETAIL_TO_TAB,
  TAB_TO_ROOT
}

/**
 * The single in-app back stack shared by the UI and its unit tests.
 *
 * Order: chat detail → its owning tab → 会话 (root) → system handles back (exit).
 * Keeping the transitions pure makes the "侧滑只回上一级、不回桌面" contract verifiable
 * without a device.
 */
internal data class SessionNavigation(val tab: RootTab = RootTab.SESSIONS, val sessions: List<String> = emptyList()) {
  val canGoBack get() = sessions.isNotEmpty() || !tab.isRoot
  fun open(id: String, child: Boolean = false) = copy(sessions = if (child && sessions.isNotEmpty()) sessions.takeWhile { it != id } + id else listOf(id))
  fun back() = if (sessions.isNotEmpty()) copy(sessions = sessions.dropLast(1)) else copy(tab = RootTab.SESSIONS)
}

/** What the root AnimatedContent shows: a tab, or a chat at [depth] levels into that tab's stack. */
internal data class NavRoute(val tab: RootTab, val session: String?, val depth: Int = if (session == null) 0 else 1)

/**
 * Horizontal direction of a route change: 1 = the new page enters from the right, -1 = from the left.
 * Going deeper (open chat / child) or to a tab further right enters from the right; going back up or
 * to a tab further left enters from the left. Derived from the routes themselves so every entry point
 * (dock, rail, back gesture, notification) agrees.
 */
internal fun slideDirection(from: NavRoute, to: NavRoute): Int = when {
  to.depth != from.depth -> if (to.depth > from.depth) 1 else -1
  to.depth == 0 && to.tab != from.tab -> if (to.tab.ordinal > from.tab.ordinal) 1 else -1
  else -> 1
}

internal object AppBackStack {
  /** Whether an in-app step exists; when false the system should handle back (leave the app). */
  fun canGoBack(detail: Boolean, tab: RootTab): Boolean = detail || !tab.isRoot

  /** Identifies what stage the back gesture represents. */
  fun backStage(detail: Boolean, tab: RootTab): BackStage = when {
    detail -> BackStage.DETAIL_TO_TAB
    !tab.isRoot -> BackStage.TAB_TO_ROOT
    else -> BackStage.NONE
  }

  /**
   * Applies exactly one back step. Only call when [canGoBack] is true.
   * - detail open → close detail, keep the tab it was opened from
   * - non-root tab → return to 会话 (root)
   * The returned detail flag is always `false`: one back step never opens a detail.
   */
  fun back(detail: Boolean, tab: RootTab): Pair<Boolean, RootTab> =
    false to if (detail) tab else RootTab.SESSIONS

  /** Selecting a tab always leaves any open detail. */
  fun selectTab(tab: RootTab): Pair<Boolean, RootTab> = false to tab
}
