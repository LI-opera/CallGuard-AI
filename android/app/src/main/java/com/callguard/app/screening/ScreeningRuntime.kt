package com.callguard.app.screening

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.*

/** Process-owned bounded executors survive service unbind; no unbounded thread/queue creation. */
object ScreeningRuntime {
    private fun bounded(name: String, threads: Int, capacity: Int) = ThreadPoolExecutor(
        threads, threads, 30, TimeUnit.SECONDS, ArrayBlockingQueue(capacity),
        ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
    private val reads = bounded("callguard-read", 2, 8)
    private val writes = bounded("callguard-write", 1, 32)
    private val deadlines = ScheduledThreadPoolExecutor(2) { task -> Thread(task, "callguard-deadline").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    @Volatile private var source: AndroidScreeningDataSource? = null
    fun coordinator(context: Context): ScreeningCoordinator {
        val data = source ?: synchronized(this) {
            source ?: AndroidScreeningDataSource(context).also { source = it }
        }
        return ScreeningCoordinator(data, reads, deadlines, writes, MonotonicClock { SystemClock.elapsedRealtime() }) { id, status ->
            Log.i("CallGuard", "$status eventId=$id")
        }
    }
}
