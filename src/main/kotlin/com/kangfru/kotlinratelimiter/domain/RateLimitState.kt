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

    data class SlidingWindowLog(
        val logs: List<Instant>
    ) : RateLimitState

    data class SlidingWindowCounter(
        val currentWindowStart: Instant,
        val currentWindowCount: Long,
        val previousWindowCount: Long,
    ) : RateLimitState

}