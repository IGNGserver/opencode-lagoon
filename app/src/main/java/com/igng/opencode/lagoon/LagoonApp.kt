package com.igng.opencode.lagoon

import android.app.Application
import com.igng.opencode.lagoon.core.CrashLog

class LagoonApp : Application() {
  override fun onCreate() {
    super.onCreate()
    // Installed before any activity, service or receiver runs so every entry point is covered.
    CrashLog.install(this)
  }
}
