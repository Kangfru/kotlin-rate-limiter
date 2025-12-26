package com.kangfru.kotlinratelimiter.algorithm

import com.kangfru.kotlinratelimiter.core.RateLimiter
import com.kangfru.kotlinratelimiter.domain.RateLimitConfig
import com.kangfru.kotlinratelimiter.domain.RateLimitResult
import com.kangfru.kotlinratelimiter.domain.RateLimitState
import com.kangfru.kotlinratelimiter.domain.RequestKey
import com.kangfru.kotlinratelimiter.storage.RateLimitStorage
import java.time.Duration
import java.time.Instant

class SlidingWindowLogRateLimiter(
    private val storage: RateLimitStorage
) : RateLimiter {
    override suspend fun <T> execute(
        key: RequestKey,
        config: RateLimitConfig,
        block: suspend () -> T
    ): RateLimitResult<T> {
        return try {
            if (tryAcquire(key, config)) {
                val result = block()
                val state = storage.get(key.value) as? RateLimitState.SlidingWindowLog
                    ?: throw IllegalStateException("State not found after successful acquire")

                RateLimitResult.Allowed(
                    value = result,
                    remaining = config.limit - state.logs.size,
                    resetAt = calculateResetTime(state.logs, config)
                )
            } else {
                val state = storage.get(key.value) as? RateLimitState.SlidingWindowLog
                    ?: throw IllegalStateException("State not found after denied")

                // 가장 오래된 타임스탬프가 윈도우 밖으로 나갈 때까지 대기
                val retryAfter = calculateRetryAfter(state.logs, config)

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
        val windowStart = now.minus(config.window)
        val state = storage.get(key.value) as? RateLimitState.SlidingWindowLog

        return if (state == null) {
            storage.save(key.value, RateLimitState.SlidingWindowLog(
                listOf(now)
            ))
            true
        } else {
            val validTimestamps = state.logs.filter { it.isAfter(windowStart) }
            if (validTimestamps.size < config.limit) {
                val newTimestamps = validTimestamps + now
                storage.save(key.value, RateLimitState.SlidingWindowLog(
                    newTimestamps
                ))
                true
            } else {
                storage.save(key.value, RateLimitState.SlidingWindowLog(
                    validTimestamps
                ))
                false
            }
        }
    }

    private fun calculateResetTime(logs: List<Instant>, config: RateLimitConfig): Instant {
        return logs.minOrNull()?.plus(config.window) ?: Instant.now().plus(config.window)
    }

    private fun calculateRetryAfter(logs: List<Instant>, config: RateLimitConfig): Duration {
        val oldestTimestamp = logs.minOrNull() ?: return Duration.ZERO
        val oldestExpiry = oldestTimestamp.plus(config.window)
        val duration = Duration.between(Instant.now(), oldestExpiry)
        return if (duration.isNegative) Duration.ZERO else duration
    }

}