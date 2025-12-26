package com.kangfru.kotlinratelimiter.algorithm

import com.kangfru.kotlinratelimiter.core.RateLimiter
import com.kangfru.kotlinratelimiter.domain.RateLimitConfig
import com.kangfru.kotlinratelimiter.domain.RateLimitResult
import com.kangfru.kotlinratelimiter.domain.RateLimitState
import com.kangfru.kotlinratelimiter.domain.RequestKey
import com.kangfru.kotlinratelimiter.storage.RateLimitStorage
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

class SlidingWindowCounterRateLimiter(
    val storage: RateLimitStorage
) : RateLimiter {
    override suspend fun <T> execute(
        key: RequestKey,
        config: RateLimitConfig,
        block: suspend () -> T
    ): RateLimitResult<T> {
        return try {
            if (tryAcquire(key, config)) {
                val result = block()
                val state = storage.get(key.value) as? RateLimitState.SlidingWindowCounter
                    ?: throw IllegalStateException("State not found after successful acquire")

                val weightedCount = calculateWeightedCount(state, config)
                val remaining = (config.limit - ceil(weightedCount).toLong()).coerceAtLeast(0)

                RateLimitResult.Allowed(
                    value = result,
                    remaining = remaining,
                    resetAt = state.currentWindowStart.plus(config.window)
                )
            } else {
                val state = storage.get(key.value) as? RateLimitState.SlidingWindowCounter
                    ?: throw IllegalStateException("State not found after denied")

                val windowEnd = state.currentWindowStart.plus(config.window)
                val retryAfter = Duration.between(Instant.now(), windowEnd).let {
                    if (it.isNegative) Duration.ZERO else it
                }
                RateLimitResult.Denied(
                    retryAfter = retryAfter,
                    limit = config.limit
                )
            }
        } catch (e: Exception) {
            RateLimitResult.Error(e)
        }
    }

    override suspend fun tryAcquire(
        key: RequestKey,
        config: RateLimitConfig
    ): Boolean {
        val now = Instant.now()
        val currentWindowStart = calculateWindowStart(now, config.window)
        val state = storage.get(key.value) as? RateLimitState.SlidingWindowCounter

        return if (state == null) {
            // 첫 요청의 경우
            storage.save(key.value, RateLimitState.SlidingWindowCounter(
                currentWindowStart = currentWindowStart,
                currentWindowCount = 1,
                previousWindowCount = 0,
            ))
            true
        } else {
            if (state.currentWindowStart == currentWindowStart) {
                // 동일 윈도우
                // 가중평균
                val weightedCount = calculateWeightedCount(state, config)
                // limit check
                if (weightedCount < config.limit) {
                    storage.save(key.value, state.copy(
                        currentWindowCount = state.currentWindowCount + 1
                    ))
                    true
                } else {
                    false
                }
            } else {
                // 다른 윈도우의 경우 == 새 윈도우임.
                storage.save(key.value, RateLimitState.SlidingWindowCounter(
                    currentWindowStart = currentWindowStart,
                    currentWindowCount = 1,
                    previousWindowCount = state.currentWindowCount
                ))
                false
            }
        }
    }

    private fun calculateWindowStart(now: Instant, window: Duration): Instant {
        val epochMilli = now.toEpochMilli()
        val windowMilli = window.toMillis()
        val windowStartMilli = (epochMilli / windowMilli) * windowMilli
        return Instant.ofEpochMilli(windowStartMilli)
    }

    private fun calculateWeightedCount(state: RateLimitState.SlidingWindowCounter, config: RateLimitConfig): Double {
        // 가중 평균 계산
        val now = Instant.now()
        val elapsed = Duration.between(state.currentWindowStart, now).toMillis()
        val windowMillis = config.window.toMillis()
        val elapsedRatio = elapsed.toDouble() / windowMillis

        val previousWeight = 1.0 - elapsedRatio

        return state.previousWindowCount * previousWeight + state.currentWindowCount
    }

}