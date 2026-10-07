package com.igng.opencode.lagoon.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 项目只在本机注册：浏览服务器目录时默认隐藏隐藏项、可按需显示；项目可从本机移除，
 * 且移除文案明确不会删除服务器上的目录与数据。JVM 单测起不了 Compose 组合，沿用源码扫描守住回归线。
 */
class ProjectBrowserRegressionTest {
  private fun source(name: String): String = listOf(
    File("app/src/main/java/com/igng/opencode/lagoon/ui/$name"),
    File("src/main/java/com/igng/opencode/lagoon/ui/$name")
  ).firstOrNull { it.isFile }?.readText() ?: error("找不到 UI 源文件：$name")

  @Test fun directoryBrowserHidesHiddenEntriesByDefault() {
    val text = source("ProjectSheets.kt")
    assertTrue("目录列表与搜索结果都应走隐藏项过滤", text.contains("BrowseFilter.entries") && text.contains("BrowseFilter.results"))
    assertTrue("浏览器应有「显示隐藏目录」开关", text.contains("显示隐藏目录") && text.contains("Switch(checked = state.browseShowHidden"))
    assertTrue("开关只改变本机投影", text.contains("controller.setBrowseShowHidden"))
  }

  @Test fun projectsAreRemovedFromThisDeviceOnly() {
    val text = source("ProjectSheets.kt")
    assertTrue("项目行应能触发本机移除", text.contains("controller.removeProject"))
    assertTrue("移除确认必须说明只删本机记录", text.contains("不会删除服务器上的任何目录或数据"))
  }
}
