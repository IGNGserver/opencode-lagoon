package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * HyperOS large-title bar whose title is a selector ("全部会话 ⌄").
 * It keeps miuix TopAppBar's metrics (56dp toolbar, 26dp title inset, 32sp large / 20sp collapsed title)
 * and drives the same [ScrollBehavior], so it collapses exactly like the other root pages; miuix's
 * own `largeTitle` only accepts a String and cannot carry the ⌄ affordance or a click target.
 */
@Composable
internal fun SelectorTopBar(
  title: String,
  onTitleClick: () -> Unit,
  scrollBehavior: ScrollBehavior,
  navigation: @Composable RowScope.() -> Unit,
  actions: @Composable RowScope.() -> Unit
) {
  val state = scrollBehavior.state
  var largeHeight by remember { mutableIntStateOf(0) }
  SideEffect { if (state.heightOffsetLimit != -largeHeight.toFloat()) state.heightOffsetLimit = -largeHeight.toFloat() }
  Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background).statusBarsPadding()) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp)) {
      Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, content = navigation)
      Row(
        Modifier.align(Alignment.Center).widthIn(max = 180.dp)
          .graphicsLayer { alpha = state.collapsedFraction }
          .clickable(onClick = onTitleClick),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
          fontSize = MiuixTheme.textStyles.title3.fontSize, fontWeight = FontWeight.Medium)
        Icon(MiuixIcons.ExpandMore, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
      }
      Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
    // The large title slides up under the toolbar and is clipped as the list scrolls.
    Layout(
      content = {
        Row(
          Modifier.onSizeChanged { largeHeight = it.height }
            .padding(start = 26.dp, end = 26.dp, bottom = 8.dp)
            .graphicsLayer { alpha = 1f - state.collapsedFraction },
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(Modifier.clickable(onClick = onTitleClick), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
              fontSize = MiuixTheme.textStyles.title1.fontSize, fontWeight = FontWeight.Normal)
            Spacer(Modifier.width(4.dp))
            Icon(MiuixIcons.ExpandMore, "切换范围", Modifier.size(26.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
          }
        }
      },
      modifier = Modifier.fillMaxWidth().clipToBounds()
    ) { measurables, constraints ->
      val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
      val offset = state.heightOffset.roundToInt()
      layout(constraints.maxWidth, (placeable.height + offset).coerceAtLeast(0)) { placeable.place(0, offset) }
    }
  }
}
