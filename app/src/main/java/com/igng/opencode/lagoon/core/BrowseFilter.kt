package com.igng.opencode.lagoon.core

/**
 * Hidden-entry rules for the remote directory browser. The server lists every entry; this device
 * decides what to show, so the default hides dot-entries without another round trip.
 */
object BrowseFilter {
  /** POSIX hidden entry: its last path segment starts with a dot. */
  fun isHiddenEntry(path: String): Boolean = path.trimEnd('/').substringAfterLast('/').startsWith(".")

  /** A search result is hidden when any of its path segments is a dot-name. */
  fun isHiddenPath(path: String): Boolean = path.split('/').any { it.startsWith(".") && it != "." && it != ".." }

  fun entries(nodes: List<FileNode>, showHidden: Boolean): List<FileNode> =
    if (showHidden) nodes else nodes.filterNot { isHiddenEntry(it.path) }

  fun results(paths: List<String>, showHidden: Boolean): List<String> =
    if (showHidden) paths else paths.filterNot { isHiddenPath(it) }
}
