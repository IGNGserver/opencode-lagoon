package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val groupTitleMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** Model selector: recent picks first, then the visible catalog grouped by provider. */
@Composable
internal fun ModelPickerSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit, onManage: () -> Unit) {
  val visible = state.visibleModels
  val byKey = state.modelCatalog.associateBy { it.key }
  val recent = state.recentModels.mapNotNull { byKey[it] }.filter { it in visible }
  val current = state.model?.let(::modelKey)
  SuperBottomSheet(title = "选择模型", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      item { ResourceHint(state.resource("models"), "服务器没有可用模型", retry = controller::reload) }
      item {
        Card(Modifier.fillMaxWidth()) {
          ChoiceRow("跟随会话", state.session?.model?.label ?: "使用会话或服务器默认模型", !state.modelChanged) { controller.chooseModel(null); onDismiss() }
        }
      }
      if (recent.isNotEmpty()) {
        item { SmallTitle("最近使用", insideMargin = groupTitleMargin) }
        item { Card(Modifier.fillMaxWidth()) { recent.forEach { model -> ModelRow(model, state.modelChanged && model.key == current) { controller.chooseModel(model.choice); onDismiss() } } } }
      }
      visible.groupBy { it.providerName }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (provider, models) ->
        item(key = "provider:$provider") { SmallTitle(provider, insideMargin = groupTitleMargin) }
        item(key = "models:$provider") {
          Card(Modifier.fillMaxWidth()) {
            models.sortedBy { it.choice.label.lowercase() }.forEach { model -> ModelRow(model, state.modelChanged && model.key == current) { controller.chooseModel(model.choice); onDismiss() } }
          }
        }
      }
      if (visible.isEmpty() && state.modelCatalog.isNotEmpty()) item {
        Text("所有模型都已隐藏，可在“管理模型”里打开。", Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1)
      }
      item { Spacer(Modifier.height(12.dp)) }
      item { Card(Modifier.fillMaxWidth()) { SuperArrow(title = "管理模型", summary = "选择这里显示哪些模型", onClick = onManage) } }
    }
  }
}

@Composable
private fun ModelRow(model: ModelInfo, selected: Boolean, onClick: () -> Unit) {
  val warning = if (model.imageInput == false) " · 不支持图片" else ""
  ChoiceRow(model.choice.label, model.choice.modelId + warning, selected, onClick)
}

/**
 * Same switches as the desktop "管理模型" dialog. The desktop keeps its switches in its own local
 * storage, so the phone cannot read them; untouched models follow the official default rule.
 */
@Composable
internal fun ManageModelsSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  var query by rememberSaveable { mutableStateOf("") }
  val latest = remember(state.modelCatalog) { ModelVisibility.latest(state.modelCatalog) }
  val shown = state.modelCatalog.filter { model ->
    query.isBlank() || listOf(model.choice.label, model.choice.modelId, model.providerName).any { it.contains(query.trim(), ignoreCase = true) }
  }
  SuperBottomSheet(title = "管理模型", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      item {
        Text("只影响这台手机。电脑端的开关存在电脑本地，这里读不到；没手动改过的模型按 OpenCode 官方规则显示：各系列半年内的最新款和自定义模型。",
          Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
      item { TextField(query, { query = it }, label = "搜索模型", useLabelAsPlaceholder = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
      item { ResourceHint(state.resource("models"), "服务器没有可用模型", retry = controller::reload) }
      shown.groupBy { it.providerName }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (provider, models) ->
        item(key = "provider:$provider") { SmallTitle(provider, insideMargin = groupTitleMargin) }
        item(key = "models:$provider") {
          Card(Modifier.fillMaxWidth()) {
            models.sortedBy { it.choice.label.lowercase() }.forEach { model ->
              SuperSwitch(
                checked = ModelVisibility.isVisible(model, latest, state.modelOverrides),
                onCheckedChange = { controller.setModelVisible(model, it) },
                title = model.choice.label,
                summary = model.choice.modelId
              )
            }
          }
        }
      }
    }
  }
}
