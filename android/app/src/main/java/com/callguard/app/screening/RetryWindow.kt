package com.callguard.app.screening

import com.callguard.app.data.entity.CallRecordEntity

/** Fixed window anchored at the first call, never extended by the second/third call. */
data class RetryWindow(val startedAtMs: Long, val retryWindowUntil: Long, val repeatCount: Int) {
    companion object {
        const val LENGTH_MS = 180_000L
        fun next(previous: CallRecordEntity?, elapsedMs: Long, boot: Int?): RetryWindow? {
            require(elapsedMs >= 0)
            if (boot == null) return null
            val start = previous?.retryStartedAtMs
            val until = previous?.retryWindowUntil
            if (previous?.bootCount == boot && start != null && until != null && elapsedMs in start..until)
                return RetryWindow(start, until, previous.repeatCount.coerceAtMost(Int.MAX_VALUE - 1) + 1)
            return RetryWindow(elapsedMs, Math.addExact(elapsedMs, LENGTH_MS), 0)
        }
    }
}
