package com.kangfru.kotlinratelimiter.core

import com.kangfru.kotlinratelimiter.domain.RateLimitConfig
import com.kangfru.kotlinratelimiter.domain.RateLimitResult
import com.kangfru.kotlinratelimiter.domain.RequestKey

interface RateLimiter {

    suspend fun <T> execute(
        key: RequestKey,
        config: RateLimitConfig,
        block: suspend () -> T
    ): RateLimitResult<T>

    suspend fun tryAcquire(
        key: RequestKey,
        config: RateLimitConfig,
    ): Boolean

}