package com.kangfru.kotlinratelimiter

import com.kangfru.kotlinratelimiter.algorithm.FixedWindowRateLimiter
import com.kangfru.kotlinratelimiter.algorithm.TokenBucketRateLimiter
import com.kangfru.kotlinratelimiter.domain.*
import com.kangfru.kotlinratelimiter.domain.RateLimitConfig.Companion.per
import com.kangfru.kotlinratelimiter.storage.InMemoryStorage
import kotlinx.coroutines.*

suspend fun main() = coroutineScope {

    tokenBucket()

    println("\n" + "=".repeat(50))
    println("FIXED WINDOW RATE LIMITER")
    println("=".repeat(50) + "\n")

    testFixedWindow()
}

suspend fun tokenBucket() = coroutineScope {
    val storage = InMemoryStorage()
    val limiter = TokenBucketRateLimiter(storage)

    println("=== Test 1: Basic Flow (3 per second) ===")
    repeat(5) { i ->
        val result = limiter.execute(
            RequestKey("user:123"),
            3.per.second
        ) {
            "Request $i processed"
        }

        when (result) {
            is RateLimitResult.Allowed ->
                println("✅ $i: ${result.value}, remaining: ${result.remaining}, resetAt: ${result.resetAt}")
            is RateLimitResult.Denied ->
                println("❌ $i: Rate limited! Retry after ${result.retryAfter.toMillis()}ms")
            is RateLimitResult.Error ->
                println("💥 $i: Error - ${result.cause.message}")
        }
    }

    println("\n=== Test 2: Token Refill (2 per second) ===")
    // 2개 소진
    repeat(2) { limiter.tryAcquire(RequestKey("user:456"), 2.per.second) }
    println("2개 요청 소진")

    // 즉시 3번째 시도 (실패 예상)
    val immediate = limiter.tryAcquire(RequestKey("user:456"), 2.per.second)
    println("즉시 3번째 시도: ${if (immediate) "✅" else "❌"}")

    // 1초 대기 (2개 리필됨)
    delay(1100)
    println("\n1초 후...")

    repeat(3) { i ->
        val allowed = limiter.tryAcquire(RequestKey("user:456"), 2.per.second)
        println("Request $i: ${if (allowed) "✅ Allowed" else "❌ Denied"}")
    }

    println("\n=== Test 3: Extension Functions ===")
    limiter.execute(RequestKey("user:789"), 10.per.minute) {
        "Payment processed successfully"
    }
        .onAllowed { value ->
            println("✅ Success: $value")
        }
        .onDenied { retryAfter ->
            println("❌ Rate limited! Wait ${retryAfter.seconds}s")
        }
        .onError { error ->
            println("💥 Error: ${error.message}")
        }

    println("\n=== Test 4: Multiple Keys (Independent) ===")
    launch {
        repeat(3) { i ->
            val allowed = limiter.tryAcquire(RequestKey("api:/users"), 2.per.second)
            println("  [/users] Request $i: ${if (allowed) "✅" else "❌"}")
            delay(400)
        }
    }

    launch {
        repeat(3) { i ->
            val allowed = limiter.tryAcquire(RequestKey("api:/orders"), 5.per.second)
            println("  [/orders] Request $i: ${if (allowed) "✅" else "❌"}")
            delay(400)
        }
    }

    delay(2000)

    println("\n=== Test 5: High Limit (100 per minute) ===")
    repeat(10) { i ->
        val result = limiter.execute(RequestKey("api:external"), 100.per.minute) {
            "API call $i"
        }

        if (result is RateLimitResult.Allowed) {
            println("✅ $i: remaining ${result.remaining}")
        }
    }

    println("\n✨ All tests completed!")
}

suspend fun testFixedWindow() = coroutineScope {
    val storage = InMemoryStorage()
    val limiter = FixedWindowRateLimiter(storage)

    println("=== Fixed Window Test 1: Basic Flow ===")
    repeat(5) { i ->
        val result = limiter.execute(RequestKey("test1"), 3.per.second) {
            "Request $i"
        }

        when (result) {
            is RateLimitResult.Allowed ->
                println("✅ $i: ${result.value}, remaining: ${result.remaining}")
            is RateLimitResult.Denied ->
                println("❌ $i: Retry after ${result.retryAfter.toMillis()}ms")
            is RateLimitResult.Error ->
                println("💥 $i: ${result.cause.message}")
        }
    }
    // 예상: ✅ ✅ ✅ ❌ ❌

    println("\n=== Fixed Window Test 2: Window Reset ===")
    repeat(3) { limiter.tryAcquire(RequestKey("test2"), 2.per.second) }
    println("2개 소진")

    val immediate = limiter.tryAcquire(RequestKey("test2"), 2.per.second)
    println("즉시 3번째 시도: ${if (immediate) "✅" else "❌"}")  // ❌

    delay(1100)  // 다음 윈도우
    println("1초 후 (새 윈도우)...")

    repeat(3) { i ->
        val allowed = limiter.tryAcquire(RequestKey("test2"), 2.per.second)
        println("Request $i: ${if (allowed) "✅" else "❌"}")
    }
    // 예상: ✅ ✅ ❌

    println("\n=== Fixed Window Test 3: Burst Problem 재현 ===")
    val config = 5.per.second
    val key = RequestKey("burst")

    // 윈도우 끝에서 5개 소진
    repeat(5) {
        limiter.tryAcquire(key, config)
    }
    println("윈도우 1: 5개 소진")

    // 정확히 다음 윈도우까지 대기
    delay(1000)
    println("다음 윈도우 시작!")

    // 즉시 5개 더 허용됨 (Burst!)
    repeat(5) { i ->
        val allowed = limiter.tryAcquire(key, config)
        println("  Request $i: ${if (allowed) "✅ Burst!" else "❌"}")
    }
    // 모두 허용! (1초 만에 10개)

    println("\n=== Fixed Window vs Token Bucket 비교 ===")
    val fixedLimiter = FixedWindowRateLimiter(InMemoryStorage())
    val tokenLimiter = TokenBucketRateLimiter(InMemoryStorage())

    println("Fixed Window:")
    repeat(3) { fixedLimiter.tryAcquire(RequestKey("compare-fixed"), 2.per.second) }
    delay(1000)
    repeat(3) { i ->
        val allowed = fixedLimiter.tryAcquire(RequestKey("compare-fixed"), 2.per.second)
        println("  $i: ${if (allowed) "✅" else "❌"}")
    }
    // 예상: ✅ ✅ ❌ (즉시 2개 허용)

    println("\nToken Bucket:")
    repeat(3) { tokenLimiter.tryAcquire(RequestKey("compare-token"), 2.per.second) }
    delay(1000)
    repeat(3) { i ->
        val allowed = tokenLimiter.tryAcquire(RequestKey("compare-token"), 2.per.second)
        println("  $i: ${if (allowed) "✅" else "❌"}")
    }
    // 예상: ✅ ✅ ❌ (점진적 리필로 2개만)
}