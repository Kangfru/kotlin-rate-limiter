package com.kangfru.kotlinratelimiter.storage

import com.kangfru.kotlinratelimiter.domain.RateLimitState

interface RateLimitStorage{
    suspend fun get(key: String): RateLimitState?
    suspend fun save(key: String, state: RateLimitState)
    suspend fun delete(key: String)
}