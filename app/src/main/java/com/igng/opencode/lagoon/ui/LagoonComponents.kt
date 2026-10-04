package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixCapsuleShape

/** A single-choice list row in MIUIX style: title, optional summary, check mark when selected. */
@Composable
internal fun ChoiceRow(title: String, summary: String? = null, selected: Boolean, onClick: () -> Unit) {
  BasicComponent(
    title = title,
    summary = summary,
    onClick = onClick,
    endActions = {
      if (selected) Icon(MiuixIcons.Ok, "已选择", Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
    }
  )
}

/**
 * Outlined capsule used for in-place selectors (model, device, project): a 1dp hairline instead of a
 * filled block, so it reads as tappable without competing with primary actions.
 */
@Composable
internal fun CapsuleSelector(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  leading: (@Composable () -> Unit)? = null,
  maxTextWidth: Dp = 200.dp
) {
  val shape = miuixCapsuleShape()
  Row(
    modifier
      .clip(shape)
      .border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape)
      .clickable(enabled = enabled, onClick = onClick)
      .heightIn(min = 32.dp)
      .padding(start = if (leading == null) 12.dp else 10.dp, end = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    leading?.let { it(); Spacer(Modifier.width(6.dp)) }
    Text(text, Modifier.widthIn(max = maxTextWidth), maxLines = 1, overflow = TextOverflow.Ellipsis,
      style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium,
        color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary))
    Icon(MiuixIcons.ExpandMore, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
  }
}

/** Status dot used next to server names: connected / recovering / offline. */
@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier) {
  Box(modifier.size(8.dp).background(color, miuixCapsuleShape()))
}

/** Circular icon action (send / stop / attach) sized like the MIUIX IconButton (40dp). */
@Composable
internal fun RoundAction(
  icon: ImageVector?,
  description: String,
  onClick: () -> Unit,
  enabled: Boolean = true,
  container: Color = MiuixTheme.colorScheme.primary,
  content: Color = MiuixTheme.colorScheme.onPrimary,
  glyph: (@Composable () -> Unit)? = null
) {
  Box(
    Modifier
      .size(40.dp)
      .clip(miuixCapsuleShape())
      .background(if (enabled) container else MiuixTheme.colorScheme.disabledPrimaryButton)
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    if (glyph != null) glyph()
    else if (icon != null) Icon(icon, description, Modifier.size(20.dp), tint = if (enabled) content else MiuixTheme.colorScheme.disabledOnPrimaryButton)
  }
}
