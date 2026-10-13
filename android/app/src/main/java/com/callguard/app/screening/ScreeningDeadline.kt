package com.callguard.app.screening

fun interface MonotonicClock { fun nowMs(): Long }

data class ScreeningDeadline(val receivedAtMs: Long, val budgetMs: Long = 800) {
    init { require(receivedAtMs >= 0 && budgetMs in 1..1500) }
    fun remainingMs(nowMs: Long): Long = (budgetMs - (nowMs - receivedAtMs).coerceAtLeast(0)).coerceAtLeast(0)
    fun expired(nowMs: Long): Boolean = remainingMs(nowMs) == 0L
}
