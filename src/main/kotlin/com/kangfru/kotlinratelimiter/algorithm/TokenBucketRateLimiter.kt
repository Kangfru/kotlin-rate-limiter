package com.kangfru.kotlinratelimiter.algorithm

import com.kangfru.kotlinratelimiter.core.RateLimiter
import com.kangfru.kotlinratelimiter.domain.RateLimitConfig
import com.kangfru.kotlinratelimiter.domain.RateLimitResult
import com.kangfru.kotlinratelimiter.domain.RequestKey
import com.kangfru.kotlinratelimiter.storage.RateLimitStorage
import com.kangfru.kotlinratelimiter.storage.TokenBucketState
import java.time.Duration
import java.time.Instant
import kotlin.math.min

class TokenBucketRateLimiter(
    private val storage: RateLimitStorage,
) : RateLimiter {

    override suspend fun <T> execute(
        key: RequestKey,
        config: RateLimitConfig,
        block: suspend () -> T
    ): RateLimitResult<T> {
        return try {
            if (tryAcquire(key, config)) {
                // 허용되면 block() 실행 후 Allowed 반환
                val result = block()
                val state = storage.get(key.value) ?: throw IllegalStateException("State not found after successful acquire")
                RateLimitResult.Allowed(result, state.tokens.toLong(), state.lastRefillTime.plus(config.window))
            } else {
                val state = storage.get(key.value) ?: throw IllegalStateException("State not found after failed acquire")
                RateLimitResult.Denied(calculateRetryAfter(state.lastRefillTime, config), config.limit)
            }
        } catch (e: Exception) {
            RateLimitResult.Error(e)
        }
    }

    override suspend fun tryAcquire(
        key: RequestKey,
        config: RateLimitConfig,
    ): Boolean {
        val now = Instant.now()
        val state = storage.get(key.value)

        return if (state == null) {
            storage.save(
                key.value, TokenBucketState(
                    tokens = config.limit - 1.0,
                    lastRefillTime = now
                )
            )
            true
        } else {
            // 힌트:
            // 1. 경과 시간 계산: Duration.between(state.lastRefillTime, now)
            val elapsed = Duration.between(state.lastRefillTime, now).toMillis() / 1000.0
            // 2. 리필할 토큰: elapsedSeconds * config.refillRate
            val tokensToAdd = elapsed * config.refillRate
            // 3. 현재 토큰: min(state.tokens + refill, config.limit)
            val currentTokens = min(state.tokens + tokensToAdd, config.limit.toDouble())
            if (currentTokens >= 1.0) {
                // 4. 토큰 있으면
                storage.save(
                    key.value, TokenBucketState(
                        tokens = currentTokens - 1.0,
                        lastRefillTime = now
                    )
                )
                true
            } else {
                // 5. 토큰 없으면
                false
            }
        }
    }

    private fun calculateRetryAfter(lastRefill: Instant, config: RateLimitConfig): Duration {
        val now = Instant.now()
        val timeForOneToken = 1.0 / config.refillRate  // 초
        val nextTokenTime = lastRefill.plusMillis((timeForOneToken * 1000).toLong())

        val duration = Duration.between(now, nextTokenTime)
        return if (duration.isNegative) Duration.ZERO else duration
    }

}