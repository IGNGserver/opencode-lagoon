package com.igng.opencode.lagoon.system

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import com.igng.opencode.lagoon.R
import com.igng.opencode.lagoon.core.LiveUpdateContent
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.TaskSummary
import org.json.JSONObject

/** 灵动岛 / 实时活动在某一设备上的可用状态，用于设置页展示与诊断。 */
data class IslandSupport(
  val vendor: String,
  val label: String,
  val supported: Boolean,
  val granted: Boolean,
  val note: String
)

/**
 * 一个厂商（或标准通道）的灵动岛扩展。约定按“能力探测 / 品牌兜底”路由，而不是把所有逻辑
 * 硬编码进通知构建：不支持时不产生任何副作用。
 */
internal interface IslandAdapter {
  val vendor: String
  /** 当前设备是否支持该通道，以及权限是否就绪。[profile] 为当前服务器资料，携带各通道开关。 */
  fun support(context: Context, profile: ServerProfile?): IslandSupport
  /** 在不改变通知语义的前提下，向 [notification] 附加该通道所需的 extras。 */
  fun extend(context: Context, profile: ServerProfile?, notification: Notification, title: String, detail: String, running: Boolean, summary: TaskSummary? = null)
}

/**
 * OPPO ColorOS 15 流体云所需的一次性参数与实际下发实现。`serviceId` 由 OPPO 开放平台分配，
 * 申请到后填入；`transport` 由接入方在拿到意图共享接口后注入，默认空实现，便于无厂商环境构建。
 */
internal object OppoFluidCloud {
  var serviceId = ""
  var transport: OppoFluidCloudTransport = NoopOppoFluidCloudTransport
}

/** 把构建好的流体云意图交给 OPPO 通道。默认空实现，便于在不依赖厂商环境时构建与测试。 */
internal interface OppoFluidCloudTransport {
  fun publish(context: Context, serviceId: String, payload: String)
}

internal object NoopOppoFluidCloudTransport : OppoFluidCloudTransport {
  override fun publish(context: Context, serviceId: String, payload: String) = Unit
}

internal fun brandKey(): String = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()

internal object IslandRegistry {
  private val adapters: List<IslandAdapter> = listOf(
    StandardLiveUpdateAdapter, VivoIslandAdapter, HonorIslandAdapter, OppoFluidCloudAdapter
  )

  fun extendAll(context: Context, profile: ServerProfile?, notification: Notification, title: String, detail: String, running: Boolean, summary: TaskSummary? = null) {
    adapters.forEach { runCatching { it.extend(context, profile, notification, title, detail, running, summary) } }
  }

  /** 全部通道的可用状态；[context] 用于读取厂商协议版本与标准通道权限。可能较慢，请在 IO 线程调用。 */
  fun diagnostics(context: Context, profile: ServerProfile?): List<IslandSupport> = adapters.map { adapter ->
    runCatching { adapter.support(context, profile) }
      .getOrElse { IslandSupport(adapter.vendor, "检测失败", false, false, it.message.orEmpty()) }
  }

  /** 测试用：已注册通道的 vendor 列表。 */
  internal fun registeredVendorsForTest(): List<String> = adapters.map { it.vendor }
}

/**
 * vivo OriginOS 原子岛 / 原子通知。使用标准 `notification.superx.*` extras 在本地创建/更新。
 * 场景与权限需向 vivo 申请（当前为公测），未获批时系统会忽略这些 extras；`showNotify` 保证
 * 退化为普通通知。刷新/展示时长受系统限制（详见 vivo 文档），故摘要通知本身为 ongoing。
 */
internal object VivoIslandAdapter : IslandAdapter {
  override val vendor = "vivo"

  private fun isVivo(): Boolean {
    val brand = brandKey()
    return "vivo" in brand || "iqoo" in brand
  }

  override fun support(context: Context, profile: ServerProfile?): IslandSupport = IslandSupport(
    vendor = vendor,
    label = "vivo 原子岛（原子通知）",
    supported = isVivo(),
    granted = false,
    note = if (isVivo()) "已写入原子岛参数；需在 vivo 开放平台申请原子通知/原子岛接入权限后方可展示。"
           else "当前设备不是 vivo / iQOO。"
  )

  override fun extend(context: Context, profile: ServerProfile?, notification: Notification, title: String, detail: String, running: Boolean, summary: TaskSummary?) {
    // Only the server-wide summary may become an island.
    if (!isVivo() || summary == null) return
    val extras = Bundle()
    extras.putInt("notification.superx.operation", 1)
    extras.putBoolean("notification.superx.showNotify", true)
    extras.putInt("notification.superx.template", 1)
    extras.putString("notification.superx.scene", "TASK")

    val vivoTitle = if (summary != null && !summary.isEmpty) {
      "${summary.running}运行·${summary.completed}完成"
    } else {
      title.take(40)
    }
    val vivoContent = if (summary != null && summary.items.isNotEmpty()) {
      summary.items.take(3).joinToString("；") { "${LiveUpdateContent.phaseLabel(it.phase)} · ${it.title}" }.take(100)
    } else {
      detail.take(100)
    }

    val baseInfo = Bundle().apply {
      putParcelable("notification.superx.baseInfos.icon", Icon.createWithResource(context, R.drawable.ic_notification))
      putCharSequence("notification.superx.baseInfos.title", vivoTitle)
      putCharSequence("notification.superx.baseInfos.content", vivoContent)
    }
    extras.putBundle("notification.superx.baseInfos", baseInfo)
    notification.extras.putAll(extras)
  }
}

/**
 * 荣耀 MagicOS 灵动胶囊 / YOYO 建议。属于白名单制的“快捷服务 / 卡片模板”通道，非通行运行时通知
 * extras，需荣耀开发者企业认证与专项对接，因此默认关闭（由服务器资料里的 `islandHonor` 开关控制）。
 * 开启后本适配器标记“已就绪”，实际下发由荣耀对接层完成。
 */
internal object HonorIslandAdapter : IslandAdapter {
  override val vendor = "honor"

  private fun isHonor(): Boolean = "honor" in brandKey()
  private fun enabled(profile: ServerProfile?): Boolean = profile?.islandHonor == true

  override fun support(context: Context, profile: ServerProfile?): IslandSupport = IslandSupport(
    vendor = vendor,
    label = "荣耀灵动胶囊（YOYO 建议）",
    supported = false,
    granted = false,
    note = when {
      !isHonor() -> "当前设备不是荣耀。"
      !enabled(profile) -> "该通道需荣耀开发者企业认证与白名单，当前未开启。"
      else -> "尚未实现荣耀企业通道，当前不可用。"
    }
  )

  override fun extend(context: Context, profile: ServerProfile?, notification: Notification, title: String, detail: String, running: Boolean, summary: TaskSummary?) {
    if (!isHonor() || !enabled(profile)) return
  }
}

/**
 * OPPO ColorOS 15 流体云（意图共享）。端侧通过「意图共享」创建 / 更新 / 结束，`actionStatus = 0/1/2`；
 * 需开放平台分配 `serviceId`。为不依赖厂商环境即可构建测试，实际下发通过 [OppoFluidCloud.transport]
 * 注入；默认空实现。ColorOS 16 无需此通道，走标准 Live Updates。
 */
internal object OppoFluidCloudAdapter : IslandAdapter {
  override val vendor = "oppo"

  private fun isOppo(): Boolean = run {
    val brand = brandKey()
    "oppo" in brand || "oneplus" in brand || "realme" in brand
  }

  private fun enabled(profile: ServerProfile?): Boolean = profile?.islandOppoFluidCloud == true && OppoFluidCloud.serviceId.isNotBlank()

  override fun support(context: Context, profile: ServerProfile?): IslandSupport = IslandSupport(
    vendor = vendor,
    label = "OPPO 流体云（ColorOS 15 意图共享）",
    supported = false,
    granted = false,
    note = when {
      !isOppo() -> "当前设备不是 OPPO / 一加 / realme。"
      !enabled(profile) -> "该通道需 OPPO 开放平台分配 serviceId，当前未开启；ColorOS 16 走标准实时更新即可。"
      else -> "尚未接入 OPPO 意图共享通道，当前不可用。"
    }
  )

  override fun extend(context: Context, profile: ServerProfile?, notification: Notification, title: String, detail: String, running: Boolean, summary: TaskSummary?) {
    if (!isOppo() || !enabled(profile)) return
    val oppoTitle = if (summary != null && !summary.isEmpty) "${summary.running}运行·${summary.completed}完成" else title.take(40)
    val oppoDetail = if (summary != null && summary.items.isNotEmpty()) {
      summary.items.take(3).joinToString("；") { it.title }.take(100)
    } else {
      detail.take(100)
    }
    val payload = JSONObject()
      .put("intentName", "OpenCode.TaskSummary")
      .put("actionStatus", if (running) 1 else 0)
      .put("entityName", "TASK")
      .put("entityId", "opencode-task-summary")
      .put("capsule", JSONObject().put("rightText", (summary?.shortText ?: detail).take(20)))
      .put("primary", JSONObject().put("title", oppoTitle).put("content", oppoDetail))
      .toString()
    runCatching { OppoFluidCloud.transport.publish(context, OppoFluidCloud.serviceId, payload) }
  }
}

/**
 * 原生 Android 16 Live Updates 通道，是本应用在所有 Android 16+ 系统上的主通道：Pixel 等 AOSP ROM、
 * OPPO ColorOS 16（流体云完整兼容该 API），以及小米澎湃 OS 3（实时更新会被映射到超级岛）。
 * 外观与配色由各系统决定。通知构建处已设置 `setRequestPromotedOngoing` / `setShortCriticalText`，
 * 此处仅用于探测与展示，无额外 extras。
 *
 * 小米焦点通知模板（`miui.focus.param`）只对小米白名单内的应用生效，本应用未申请，因此不再写入。
 */
internal object StandardLiveUpdateAdapter : IslandAdapter {
  override val vendor = "android"

  override fun support(context: Context, profile: ServerProfile?): IslandSupport {
    val api = Build.VERSION.SDK_INT >= 36
    val manager = context.getSystemService(NotificationManager::class.java)
    val granted = api && runCatching { manager?.canPostPromotedNotifications() == true }.getOrDefault(false)
    return IslandSupport(
      vendor = vendor,
      label = "系统实时更新（Android 16 Live Updates）",
      supported = api,
      granted = granted,
      note = when {
        !api -> "需要 Android 16（API 36）及以上。已随标准通知照常显示。"
        granted -> "任务运行或等待处理时，总览会以系统样式显示在状态栏胶囊 / 超级岛 / 流体云；全部结束后变为普通通知。"
        else -> "系统尚未允许实时更新。请在设置中授予「实时更新 / 提升为常驻通知」权限后重试。"
      }
    )
  }

  override fun extend(context: Context, profile: ServerProfile?, notification: Notification, title: String, detail: String, running: Boolean, summary: TaskSummary?) = Unit
}
