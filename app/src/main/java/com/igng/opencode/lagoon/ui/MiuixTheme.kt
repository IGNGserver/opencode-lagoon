package com.igng.opencode.lagoon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/**
 * MIUIX 全局主题提供器：
 * 采用 MIUIX 官方 ThemeController，支持跟随系统或强制明暗模式，
 * 明暗模式由应用偏好与系统配置共同决定。
 */
@Composable
fun LagoonMiuixTheme(
  dark: Boolean,
  content: @Composable () -> Unit
) {
  val mode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light
  val controller = remember(dark) {
    ThemeController(
      colorSchemeMode = mode,
      isDark = dark
    )
  }

  MiuixTheme(
    controller = controller,
    content = content
  )
}

@Composable
fun OpenCodeMiuixTheme(
  dark: Boolean,
  content: @Composable () -> Unit
) = LagoonMiuixTheme(dark, content)
