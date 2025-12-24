package com.kangfru.kotlinratelimiter.domain

import java.time.Instant

sealed interface RateLimitState {

    data class TokenBucket(
        val tokens: Double,
        val lastRefillTime: Instant
    ) : RateLimitState

    data class FixedWindow(
        val counter: Long,
        val windowStart: Instant
    ) : RateLimitState

}