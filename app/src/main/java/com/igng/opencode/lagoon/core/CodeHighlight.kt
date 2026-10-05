package com.igng.opencode.lagoon.core

/** 代码面板里一行的语义，决定渲染颜色。 */
enum class CodeToken { PLAIN, HEADER, HUNK, ADD, REMOVE, COMMAND }

/** 一段连续的、同语义的文本（包含换行）。 */
data class CodeFragment(val text: String, val token: CodeToken)

/**
 * 轻量代码高亮：不引入完整语法解析，只按行首判定最常见的几类，足够让终端输出与 diff 有颜色。
 * 纯函数、不依赖 Compose，便于单测；颜色映射由 UI 层负责。
 */
object CodeHighlight {
  fun split(text: String): List<CodeFragment> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split('\n')
    val out = mutableListOf<CodeFragment>()
    lines.forEachIndexed { index, line ->
      val token = classify(line)
      val chunk = if (index == lines.lastIndex) line else line + "\n"
      if (out.isNotEmpty() && out.last().token == token) out[out.size - 1] = out.last().copy(text = out.last().text + chunk)
      else out += CodeFragment(chunk, token)
    }
    return out
  }

  internal fun classify(line: String): CodeToken = when {
    line.startsWith("+++") || line.startsWith("---") -> CodeToken.HEADER
    line.startsWith("diff ") || line.startsWith("index ") -> CodeToken.HEADER
    line.startsWith("@@") -> CodeToken.HUNK
    line.startsWith("+") -> CodeToken.ADD
    line.startsWith("-") -> CodeToken.REMOVE
    line.startsWith("$ ") || line == "$" -> CodeToken.COMMAND
    else -> CodeToken.PLAIN
  }
}
