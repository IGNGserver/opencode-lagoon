package com.igng.opencode.lagoon.ui

/**
 * Pages of the app. There is no tab bar: 会话 is the only root, and 已归档 / 设置 are pushed over it
 * from its ⋯ menu like any other page.
 *
 * 信息架构主线：Server → Project/Directory → Session → Conversation。
 */
internal enum class RootPage(val label: String) {
  HOME("会话"),
  ARCHIVED("已归档"),
  SETTINGS("设置")
}

/**
 * The single in-app back stack shared by the UI and its unit tests.
 *
 * Order: chat (and the children opened from it) → the page it was opened from → 会话 → system back
 * (leave the app). Keeping the transitions pure makes the "侧滑只回上一级、不回桌面" contract
 * verifiable without a device.
 */
internal data class SessionNavigation(val page: RootPage = RootPage.HOME, val sessions: List<String> = emptyList()) {
  val canGoBack get() = sessions.isNotEmpty() || page != RootPage.HOME
  val route get() = NavRoute(page, sessions.lastOrNull(), sessions.size + if (page == RootPage.HOME) 0 else 1)
  fun open(id: String, child: Boolean = false) = copy(sessions = if (child && sessions.isNotEmpty()) sessions.takeWhile { it != id } + id else listOf(id))
  /** Opening a page from the home ⋯ menu always starts from 会话, never on top of a chat. */
  fun push(next: RootPage) = SessionNavigation(next)
  fun back() = if (sessions.isNotEmpty()) copy(sessions = sessions.dropLast(1)) else SessionNavigation()
}

/** Route id of the blank "新会话" page; it becomes the real session id after the first send. */
internal const val DRAFT_SESSION = "\u0000draft"

/** What the root AnimatedContent shows: a page, or a chat [depth] levels above 会话. */
internal data class NavRoute(val page: RootPage, val session: String?, val depth: Int = if (session == null) 0 else 1)

/**
 * Horizontal direction of a route change: 1 = the new page enters from the right, -1 = from the left.
 * Going deeper enters from the right and going back enters from the left, whatever the entry point
 * (menu, back gesture, notification).
 */
internal fun slideDirection(from: NavRoute, to: NavRoute): Int = if (to.depth < from.depth) -1 else 1
