package com.igng.opencode.lagoon.system

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.igng.opencode.lagoon.core.LagoonController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * 后台定时刷新：应用不在前台、也没有任务监控服务时，每 15 分钟（系统允许的最短周期）读一次服务器。
 * 本机见过在运行、现在已结束的任务因此仍会标为“已完成”并发出完成通知，即使进程曾被冻结或回收。
 */
class BackgroundSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    // The controller is single-threaded on the main dispatcher; the network itself runs on IO inside it.
    withTimeoutOrNull(TimeUnit.SECONDS.toMillis(60)) {
      withContext(Dispatchers.Main) { LagoonController.get(applicationContext).backgroundSync() }
    }
    return Result.success()
  }

  companion object {
    private const val NAME = "lagoon-background-sync"

    fun schedule(context: Context, enabled: Boolean) {
      val work = WorkManager.getInstance(context)
      if (!enabled) { work.cancelUniqueWork(NAME); return }
      val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
      work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }
}
