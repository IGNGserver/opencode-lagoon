package com.igng.opencode.lagoon.system

import android.content.Context
import com.igng.opencode.lagoon.core.LiveUpdateStage

/** Remembers, per server, the stage at which the user swiped the summary notification away. */
internal object SummaryDismissals {
  private const val PREFS = "summary_dismissals"

  fun stage(context: Context, serverId: String): LiveUpdateStage? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(serverId, null)
      ?.let { name -> LiveUpdateStage.entries.firstOrNull { it.name == name } }

  fun record(context: Context, serverId: String, stage: LiveUpdateStage) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(serverId, stage.name).apply()
  }

  fun clear(context: Context, serverId: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(serverId).apply()
  }
}
