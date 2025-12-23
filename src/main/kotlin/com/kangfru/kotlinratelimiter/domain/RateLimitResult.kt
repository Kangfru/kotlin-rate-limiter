package com.kangfru.kotlinratelimiter.domain

import java.time.Duration
import java.time.Instant

sealed interface RateLimitResult<out T> {

    data class Allowed<T>(
        val value: T,
        val remaining: Long,
        val resetAt: Instant
    ) : RateLimitResult<T>

    data class Denied(
        val retryAfter: Duration,
        val limit: Long
    ) : RateLimitResult<Nothing>

    data class Error(
        val cause: Throwable,
    ) : RateLimitResult<Nothing>
}

suspend fun <T> RateLimitResult<T>.onAllowed(block: suspend (T) -> Unit): RateLimitResult<T> {
    if (this is RateLimitResult.Allowed) block(value)
    return this
}

suspend fun <T> RateLimitResult<T>.onDenied(block: suspend (Duration) -> Unit): RateLimitResult<T> {
    if (this is RateLimitResult.Denied) block(retryAfter)
    return this
}

suspend fun <T> RateLimitResult<T>.onError(block: suspend (Throwable) -> Unit): RateLimitResult<T> {
    if (this is RateLimitResult.Error) block(cause)
    return this
}