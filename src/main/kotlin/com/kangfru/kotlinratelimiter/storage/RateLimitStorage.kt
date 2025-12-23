package com.kangfru.kotlinratelimiter.storage

import java.time.Instant

interface RateLimitStorage{
    suspend fun get(key: String): TokenBucketState?
    suspend fun save(key: String, state: TokenBucketState)
    suspend fun delete(key: String)
}

data class TokenBucketState(
    val tokens: Double,
    val lastRefillTime: Instant
)