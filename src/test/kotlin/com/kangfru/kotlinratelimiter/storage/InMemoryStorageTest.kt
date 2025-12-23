package com.kangfru.kotlinratelimiter.storage

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import kotlin.test.Test

class InMemoryStorageTest {

    @Test
    fun `should save and get state`() = runTest {
        val storage = InMemoryStorage()
        val state = TokenBucketState(100.0, Instant.now())

        storage.save("key1", state)
        val retrievedState = storage.get("key1")

        assertNotNull(retrievedState)
        assertEquals(state, retrievedState)
    }

    @Test
    fun `should handle concurrent saves safely`() = runTest {
        val storage = InMemoryStorage()
        val key = "key2"

        val jobs = (1..10_000).map { i ->
            async {
                storage.save(key, TokenBucketState(i.toDouble(), Instant.now()))
            }
        }
        jobs.awaitAll()

        // 단 하나만 존재
        assertNotNull(storage.get(key))
    }

    @Test
    fun `should delete state completely`() = runTest {
        val storage = InMemoryStorage()
        val state = TokenBucketState(100.0, Instant.now())

        storage.save("key1", state)
        storage.delete("key1")

        assertNull(storage.get("key1"))
    }

    @Test
    fun `should handle concurrent delete and save`() = runTest {
        val storage = InMemoryStorage()
        val key = "race-key"

        repeat(100) {
            launch { storage.save(key, TokenBucketState(1.0, Instant.now())) }
            launch { storage.delete(key) }
        }
    }
}