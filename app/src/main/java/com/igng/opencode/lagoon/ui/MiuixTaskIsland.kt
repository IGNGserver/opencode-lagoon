package com.igng.opencode.lagoon.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.lagoon.core.TaskPhase
import com.igng.opencode.lagoon.core.TaskState
import com.igng.opencode.lagoon.core.TaskSummary
import com.igng.opencode.lagoon.core.TaskSummaryItem
import top.yukonga.miuix.kmp.basic.Text

/**
 * 遵循小米 HyperOS 灵动岛 / 超级岛设计规范的实时任务状态胶囊（参考 Qoder 灵动岛交互）。
 *
 * 核心设计准则：
 * 1. 物理打孔与系统灵动岛始终为纯黑色盲区，因此灵动岛无论在系统浅色或深色模式下，
 *    展开和收起均保持深曜黑底色（#121214）配合精细微高光描边（#2A2A2E），
 *    与前摄打孔浑然一体，绝不跟随浅色模式变浅。
 * 2. 收起状态（胶囊）：
 *    同时显示运行中和已完成数量（如 "0 运行 · 1 完成" 或 "1 运行 · 2 完成"），有待办/失败时追加。
 * 3. 展开状态（Qoder 风格展开浮岛卡片）：
 *    去除废话大标题，顶部为小字体紧凑状态条；
 *    下方以精致小字体逐行显示具体有哪些任务在运行、哪些已结束（最多显示 3 个）；
 *    点击具体任务行可直接跳转至对应会话。
 */
@Composable
internal fun MiuixTaskIsland(
  summary: TaskSummary,
  onOpenSession: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  val isVisible = !summary.isEmpty

  AnimatedVisibility(
    visible = isVisible,
    enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
      slideInVertically(spring(dampingRatio = 0.75f, stiffness = 320f)) { -it },
    exit = fadeOut(spring(stiffness = Spring.StiffnessHigh)) +
      slideOutVertically(spring(stiffness = Spring.StiffnessHigh)) { -it },
    modifier = modifier
  ) {
    if (!summary.isEmpty) {
      var isExpanded by remember { mutableStateOf(false) }

      val isRunning = summary.running > 0
      val isWaiting = summary.waiting > 0
      val isFailed = summary.failed > 0

      val infiniteTransition = rememberInfiniteTransition(label = "pulse")
      val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
          animation = tween(900, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
      )

      val dotColor = when {
        isFailed -> Color(0xFFF87171)
        isWaiting -> Color(0xFFFBBF24)
        isRunning -> Color(0xFF38BDF8)
        else -> Color(0xFF4ADE80)
      }

      val capsuleText = buildString {
        append("${summary.running} 运行 · ${summary.completed} 完成")
        if (summary.waiting > 0) append(" · ${summary.waiting} 待回复")
        if (summary.failed > 0) append(" · ${summary.failed} 失败")
      }

      val islandShape = remember(isExpanded) {
        if (isExpanded) miuixSquircleShape(20.dp) else miuixSquircleShape(18.dp)
      }

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(
          modifier = Modifier
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 380f))
            .clip(islandShape)
            .background(Color(0xFF121214))
            .border(width = 0.8.dp, color = Color(0xFF2A2A2E), shape = islandShape)
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = { isExpanded = !isExpanded }
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
          horizontalAlignment = Alignment.Start
        ) {
          // 头部行：呼吸点 + 计数摘要 + 展开/收起箭头指示
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Box(
              modifier = Modifier
                .size(7.dp)
                .background(
                  color = dotColor.copy(alpha = if (isRunning) pulseAlpha else 1f),
                  shape = CircleShape
                )
            )
            Text(
              text = capsuleText,
              fontSize = 12.sp,
              color = Color(0xFFF4F4F5),
              fontWeight = FontWeight.Medium,
              maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Text(
              text = if (isExpanded) "▲" else "▼",
              fontSize = 10.sp,
              color = Color(0xFF71717A)
            )
          }

          // 展开内容：参考 Qoder 灵动岛，以小字体展示具体任务明细（最多 3 项）
          if (isExpanded) {
            Spacer(Modifier.height(8.dp))
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(0.6.dp)
                .background(Color(0xFF27272A))
            )
            Spacer(Modifier.height(8.dp))

            val displayItems = summary.items.take(3)
            if (displayItems.isEmpty()) {
              Text(
                text = "暂无活跃任务明细",
                fontSize = 11.sp,
                color = Color(0xFFA1A1AA),
                modifier = Modifier.padding(vertical = 2.dp)
              )
            } else {
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                displayItems.forEach { item ->
                  TaskItemRow(item = item, onClick = { onOpenSession(item.sessionId) })
                }
              }
            }

            if (summary.running + summary.waiting + summary.completed + summary.failed > 3) {
              Spacer(Modifier.height(4.dp))
              Text(
                text = "点击任务快速进入会话",
                fontSize = 10.sp,
                color = Color(0xFF52525B),
                modifier = Modifier.align(Alignment.End)
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TaskItemRow(
  item: TaskSummaryItem,
  onClick: () -> Unit
) {
  val isRunning = item.phase in TaskState.RUNNING_PHASES
  val isWaiting = item.phase in TaskState.WAITING_PHASES
  val isFailed = item.phase == TaskPhase.FAILED

  val statusColor = when {
    isFailed -> Color(0xFFF87171)
    isWaiting -> Color(0xFFFBBF24)
    isRunning -> Color(0xFF38BDF8)
    else -> Color(0xFF4ADE80)
  }

  val statusLabel = when {
    isFailed -> "失败"
    isWaiting -> "待处理"
    isRunning -> "运行中"
    else -> "已完成"
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(miuixSquircleShape(8.dp))
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
      )
      .padding(vertical = 3.dp, horizontal = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    Box(
      modifier = Modifier
        .size(5.dp)
        .background(statusColor, CircleShape)
    )
    Text(
      text = item.title,
      fontSize = 11.sp,
      color = Color(0xFFE4E4E7),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f)
    )
    Text(
      text = statusLabel,
      fontSize = 10.sp,
      color = statusColor.copy(alpha = 0.9f),
      fontWeight = FontWeight.Medium
    )
  }
}
