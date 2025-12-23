package com.kangfru.kotlinratelimiter.domain

import java.time.Duration

data class RateLimitConfig(
    val limit: Long,
    val window: Duration
) {

    init {
        require(limit > 0) { "Limit must be positive: $limit" }
        require(window > Duration.ZERO) { "Window must be positive: $window" }
    }

    val refillRate: Double
        get() = limit.toDouble() / window.toMillis() * 1000.0 // tokens per second

    companion object {
        val Int.per: ConfigBuilder get() = ConfigBuilder(this.toLong())
        val Long.per: ConfigBuilder get() = ConfigBuilder(this)
    }

    class ConfigBuilder internal constructor(private val limit: Long) {
        val second: RateLimitConfig get() = RateLimitConfig(limit, Duration.ofSeconds(1))
        val minute: RateLimitConfig get() = RateLimitConfig(limit, Duration.ofMinutes(1))
        val hour: RateLimitConfig get() = RateLimitConfig(limit, Duration.ofHours(1))
    }
}