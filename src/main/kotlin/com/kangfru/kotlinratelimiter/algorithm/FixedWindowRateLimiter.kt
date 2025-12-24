package com.kangfru.kotlinratelimiter.algorithm

import com.kangfru.kotlinratelimiter.core.RateLimiter
import com.kangfru.kotlinratelimiter.domain.RateLimitConfig
import com.kangfru.kotlinratelimiter.domain.RateLimitResult
import com.kangfru.kotlinratelimiter.domain.RateLimitState
import com.kangfru.kotlinratelimiter.domain.RequestKey
import com.kangfru.kotlinratelimiter.storage.RateLimitStorage
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 1. 현재 시간으로 windowStart 계산
 *    now = 10:37:42
 *    window = 1 minute
 *    → windowStart = 10:37:00 (분 단위로 truncate)
 *
 * 2. Storage에서 상태 조회
 *
 * 3. 상태 없음 (첫 요청):
 *    → FixedWindow(counter = 1, windowStart = currentWindowStart)
 *    → return true
 *
 * 4. 상태 있음:
 *    a) 같은 윈도우? (state.windowStart == currentWindowStart)
 *       YES → counter < limit?
 *             YES → counter++, return true
 *             NO  → return false
 *    b) 다른 윈도우? (새 윈도우 시작)
 *       → FixedWindow(counter = 1, windowStart = currentWindowStart)
 *       → return true
 */
class FixedWindowRateLimiter(
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
                val state = storage.get(key.value) as? RateLimitState.FixedWindow
                    ?: throw IllegalStateException("State not found after successful acquire")
                RateLimitResult.Allowed(result, config.limit - state.counter, state.windowStart.plus(config.window))
            } else {
                val state = storage.get(key.value) as? RateLimitState.FixedWindow
                    ?: throw IllegalStateException("State not found after failed acquire")

                // 현재 윈도우 끝까지 남은 시간
                val windowEnd = state.windowStart.plus(config.window)
                val retryAfter = Duration.between(Instant.now(), windowEnd).let {
                    if (it.isNegative) Duration.ZERO else it
                }
                RateLimitResult.Denied(retryAfter, config.limit)
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
        val state = storage.get(key.value) as? RateLimitState.FixedWindow

        return if (state == null) {
            // 첫 요청의 경우
            storage.save(
                key.value,
                RateLimitState.FixedWindow(
                    windowStart = currentWindowStart,
                    counter = 1
                )
            )
            true
        } else {
            if (state.windowStart == currentWindowStart) {
                // 같은 윈도우의 경우
                if (state.counter < config.limit) {
                    // 아직 윈도우 내에 요청 수용 가능한 경우
                    storage.save(
                        key.value,
                        state.copy(counter = state.counter + 1)
                    )
                    true
                } else {
                    false
                }
            } else {
                // 새 윈도우로 교체
                storage.save(
                    key.value,
                    RateLimitState.FixedWindow(
                        windowStart = currentWindowStart,
                        counter = 1
                    )
                )
                true
            }
        }
    }

    private fun calculateWindowStart(now: Instant, window: Duration): Instant {
        // 예시:
        // now = 10:37:42.567
        // window = 1 minute (60000 ms)
        // → windowStart = 10:37:00.000
        //
        // 로직:
        val epochMilli = now.toEpochMilli()
        val windowMilli = window.toMillis()
        val windowStartMilli = (epochMilli / windowMilli).toInt() * windowMilli
        return Instant.ofEpochMilli(windowStartMilli)
    }

}