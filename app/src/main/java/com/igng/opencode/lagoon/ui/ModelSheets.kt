package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The composer's Agent / Model / thinking-strength selectors. Each is a compact anchored dropdown
 * (capsule + MIUIX popup), never a half-screen sheet, and none of them expose model management.
 */

/** The only agent choices the phone offers: build and plan, with no descriptions. */
private val AGENT_OPTIONS = listOf("build", "plan")

/** One row of a compact dropdown; a null [value] means "follow the session / default". */
internal data class DropdownOption(val label: String, val value: String?, val selected: Boolean)

/** Capsule plus an anchored MIUIX popup. */
@Composable
private fun Dropdown(
  text: String,
  options: List<DropdownOption>,
  onSelect: (String?) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  leading: (@Composable () -> Unit)? = null,
  maxTextWidth: Dp = 200.dp
) {
  var open by remember { mutableStateOf(false) }
  Box(modifier) {
    CapsuleSelector(text, { open = true }, enabled = enabled, leading = leading, maxTextWidth = maxTextWidth)
    MenuPopup(open, { open = false }, listOf(MenuSection(options.map { option ->
      MenuAction(option.label, selected = option.selected) { onSelect(option.value) }
    })), alignment = PopupPositionProvider.Align.Start)
  }
}

/** Agent selector: 默认 / build / plan. [iconOnly] is the collapsed pill's glyph button. */
@Composable
internal fun AgentSelector(
  state: LagoonState,
  controller: LagoonController,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  iconOnly: Boolean = false
) {
  var open by remember { mutableStateOf(false) }
  val options = AGENT_OPTIONS.map { DropdownOption(it, it, state.agentChanged && state.agent == it) }
  Box(modifier) {
    if (iconOnly) IconButton(onClick = { open = true }, enabled = enabled) {
      Icon(MiuixIcons.ContactsCircle, "Agent：${state.agent ?: "默认"}", Modifier.size(22.dp),
        tint = if (state.agentChanged) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    } else CapsuleSelector(state.agent ?: "默认", { open = true }, enabled = enabled, maxTextWidth = 72.dp,
      leading = { Icon(MiuixIcons.ContactsCircle, null, Modifier.size(16.dp), tint = if (state.agentChanged) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary) })
    MenuPopup(open, { open = false }, listOf(MenuSection(options.map { option ->
      MenuAction(option.label, selected = option.selected) { controller.chooseAgent(option.value) }
    })), alignment = PopupPositionProvider.Align.Start)
  }
}

/** Model selector: the session default or one of the server's visible models. */
@Composable
internal fun ModelSelector(state: LagoonState, controller: LagoonController, modifier: Modifier = Modifier, enabled: Boolean = true) {
  var open by remember { mutableStateOf(false) }
  val visible = state.visibleModels
  val currentKey = state.model?.let(::modelKey)
  val options = buildList {
    add(DropdownOption("默认模型", null, !state.modelChanged))
    visible.forEach { model -> add(DropdownOption(model.choice.label, model.key, state.modelChanged && model.key == currentKey)) }
  }
  Box(modifier) {
    CapsuleSelector(state.model?.label ?: "默认模型", { open = true }, enabled = enabled, maxTextWidth = 180.dp)
    MenuPopup(open, { open = false }, listOf(MenuSection(options.map { option ->
      MenuAction(option.label, selected = option.selected) {
        controller.chooseModel(option.value?.let { key -> visible.firstOrNull { it.key == key }?.choice })
      }
    })), alignment = PopupPositionProvider.Align.Start)
  }
}

/** Thinking-strength selector, backed only by the variants the server lists for the chosen model. */
@Composable
internal fun VariantSelector(state: LagoonState, controller: LagoonController, modifier: Modifier = Modifier, enabled: Boolean = true) {
  var open by remember { mutableStateOf(false) }
  val variants = state.modelCatalog.firstOrNull { it.key == state.model?.let(::modelKey) }?.variants.orEmpty()
  val current = state.model?.variant
  val label = variants.firstOrNull { it.id == current }?.label ?: "默认"
  Box(modifier) {
    CapsuleSelector(label, { open = true }, enabled = enabled && variants.isNotEmpty(), maxTextWidth = 84.dp)
    if (variants.isNotEmpty()) MenuPopup(open, { open = false }, listOf(MenuSection(buildList {
      add(MenuAction("默认", selected = current == null) { state.model?.let { controller.chooseModel(it.copy(variant = null)) } })
      variants.forEach { variant -> add(MenuAction(variant.label, selected = current == variant.id) { state.model?.let { controller.chooseModel(it.copy(variant = variant.id)) } }) }
    })), alignment = PopupPositionProvider.Align.Start)
  }
}
