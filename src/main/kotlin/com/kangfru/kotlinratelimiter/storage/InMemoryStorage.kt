package com.kangfru.kotlinratelimiter.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class InMemoryStorage : RateLimitStorage {
    private val storage = ConcurrentHashMap<String, TokenBucketState>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun get(key: String): TokenBucketState? = storage[key]

    override suspend fun save(key: String, state: TokenBucketState) {
        val mutex = locks.getOrPut(key) { Mutex() }
        mutex.withLock { storage[key] = state }
    }

    override suspend fun delete(key: String) {
        val mutex = locks.getOrPut(key) { Mutex() }
        mutex.withLock {
            storage.remove(key)
            locks.remove(key)
        }
    }

}