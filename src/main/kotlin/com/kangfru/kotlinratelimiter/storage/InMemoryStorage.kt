package com.kangfru.kotlinratelimiter.storage

import com.kangfru.kotlinratelimiter.domain.RateLimitState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class InMemoryStorage : RateLimitStorage {
    private val storage = ConcurrentHashMap<String, RateLimitState>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun get(key: String): RateLimitState? = storage[key]

    override suspend fun save(key: String, state: RateLimitState) {
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