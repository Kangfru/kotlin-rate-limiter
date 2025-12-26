package com.kangfru.kotlinratelimiter

import com.kangfru.kotlinratelimiter.algorithm.FixedWindowRateLimiter
import com.kangfru.kotlinratelimiter.algorithm.SlidingWindowCounterRateLimiter
import com.kangfru.kotlinratelimiter.algorithm.SlidingWindowLogRateLimiter
import com.kangfru.kotlinratelimiter.algorithm.TokenBucketRateLimiter
import com.kangfru.kotlinratelimiter.domain.*
import com.kangfru.kotlinratelimiter.domain.RateLimitConfig.Companion.per
import com.kangfru.kotlinratelimiter.storage.InMemoryStorage
import kotlinx.coroutines.*

suspend fun main() = coroutineScope {

    println("\n" + "=".repeat(50))
    println("TOKEN BUCKET RATE LIMITER")
    println("=".repeat(50) + "\n")

    tokenBucket()

    println("\n" + "=".repeat(50))
    println("FIXED WINDOW RATE LIMITER")
    println("=".repeat(50) + "\n")

    testFixedWindow()

    println("\n" + "=".repeat(50))
    println("SLIDING WINDOW LOG RATE LIMITER")
    println("=".repeat(50) + "\n")

    testSlidingWindowLog()

    println("\n" + "=".repeat(50))
    println("SLIDING WINDOW COUNTER RATE LIMITER")
    println("=".repeat(50) + "\n")

    testSlidingWindowCounter()
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


suspend fun testSlidingWindowLog() = coroutineScope {
    val storage = InMemoryStorage()
    val limiter = SlidingWindowLogRateLimiter(storage)

    println("=== Sliding Window Log Test 1: Basic Flow ===")
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

    println("\n=== Sliding Window Log Test 2: Gradual Allow ===")
    repeat(3) { limiter.tryAcquire(RequestKey("test2"), 3.per.second) }
    println("3개 소진")

    delay(400)  // 0.4초 대기
    println("0.4초 후...")

    // 아직 거부 (3개 모두 윈도우 내)
    val attempt1 = limiter.tryAcquire(RequestKey("test2"), 3.per.second)
    println("4번째 시도: ${if (attempt1) "✅" else "❌"}")  // ❌

    delay(700)  // 추가 0.7초 (총 1.1초)
    println("1.1초 후...")

    // 첫 번째 타임스탬프가 윈도우 밖으로! (1개 슬롯 생김)
    val attempt2 = limiter.tryAcquire(RequestKey("test2"), 3.per.second)
    println("5번째 시도: ${if (attempt2) "✅" else "❌"}")  // ✅

    println("\n=== Sliding Window Log Test 3: NO Burst! ===")
    val key = RequestKey("no-burst")
    val config = 5.per.second

    // 0.9초 시점에 5개 소진
    repeat(5) { limiter.tryAcquire(key, config) }
    println("5개 소진")

    delay(200)  // 1.1초 시점
    println("0.2초 후 (총 1.1초)...")

    // Fixed Window였다면: 새 윈도우로 5개 허용
    // Sliding Window: 아직 4개만 윈도우 밖
    repeat(5) { i ->
        val allowed = limiter.tryAcquire(key, config)
        println("  Request $i: ${if (allowed) "✅" else "❌"}")
        delay(100)  // 0.1초씩 대기
    }
    // 예상: ❌ (1개는 아직 윈도우 내)
    //       ✅ ✅ ✅ ✅ (점진적으로 허용)

    println("\n=== 알고리즘 비교: Burst at Boundary ===")
    val fixedLimiter = FixedWindowRateLimiter(InMemoryStorage())
    val slidingLimiter = SlidingWindowLogRateLimiter(InMemoryStorage())

    println("Fixed Window:")
    repeat(3) { fixedLimiter.tryAcquire(RequestKey("compare-fixed"), 3.per.second) }
    delay(1000)  // 새 윈도우
    repeat(3) { i ->
        val allowed = fixedLimiter.tryAcquire(RequestKey("compare-fixed"), 3.per.second)
        println("  $i: ${if (allowed) "✅ Burst!" else "❌"}")
    }
    // 예상: ✅ ✅ ✅ (즉시 3개 허용 - Burst!)

    println("\nSliding Window Log:")
    repeat(3) { slidingLimiter.tryAcquire(RequestKey("compare-sliding"), 3.per.second) }
    delay(1000)
    repeat(3) { i ->
        val allowed = slidingLimiter.tryAcquire(RequestKey("compare-sliding"), 3.per.second)
        println("  $i: ${if (allowed) "✅" else "❌"}")
        if (!allowed) delay(100)  // 조금씩 대기
    }
    // 예상: ✅ ✅ ✅ (3개 타임스탬프가 슬라이드 아웃되면서 점진적 허용)

    println("\n=== Sliding Window Log Test 4: 메모리 확인 ===")
    val memKey = RequestKey("memory-test")
    println("10개 요청 처리...")
    repeat(10) { limiter.tryAcquire(memKey, 10.per.minute) }

    val state = storage.get(memKey.value) as? RateLimitState.SlidingWindowLog
    println("저장된 타임스탬프 수: ${state?.logs?.size}")
    println("타임스탬프 샘플: ${state?.logs?.take(3)}")
}

suspend fun testSlidingWindowCounter() = coroutineScope {
    val storage = InMemoryStorage()
    val limiter = SlidingWindowCounterRateLimiter(storage)

    println("=== Sliding Window Counter Test 1: Basic Flow ===")
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

    println("\n=== Sliding Window Counter Test 2: Weighted Average ===")
    val key = RequestKey("weighted")
    val config = 10.per.second

    // 첫 번째 윈도우: 8개 소진
    repeat(8) { limiter.tryAcquire(key, config) }
    println("윈도우 1: 8개 소진")

    // 새 윈도우 시작 직후 (경과 0%)
    delay(1000)
    println("윈도우 2 시작 (0% 경과)")

    // weightedCount = 8 * (1 - 0) + 0 = 8
    // 2개만 허용되어야 함
    repeat(5) { i ->
        val allowed = limiter.tryAcquire(key, config)
        println("  Request $i: ${if (allowed) "✅" else "❌"}")
        if (allowed) delay(10)  // 약간 시간 경과
    }
    // 예상: ✅ ✅ ❌ ❌ ❌

    println("\n=== Sliding Window Counter Test 3: 시간 경과에 따른 변화 ===")
    val key2 = RequestKey("time-based")

    // 이전 윈도우: 10개 소진
    repeat(10) { limiter.tryAcquire(key2, 10.per.second) }
    println("윈도우 1: 10개 소진")

    // 새 윈도우 + 50% 경과 (500ms)
    delay(1500)  // 1초 대기 + 500ms
    println("윈도우 2 + 50% 경과")
    // weightedCount = 10 * 0.5 + 0 = 5
    // 5개 허용 가능

    repeat(7) { i ->
        val allowed = limiter.tryAcquire(key2, 10.per.second)
        println("  Request $i: ${if (allowed) "✅" else "❌"}")
    }
    // 예상: ✅ ✅ ✅ ✅ ✅ ❌ ❌

    println("\n=== 알고리즘 비교: Burst 방어 ===")
    val fixedLimiter = FixedWindowRateLimiter(InMemoryStorage())
    val counterLimiter = SlidingWindowCounterRateLimiter(InMemoryStorage())

    // 시나리오: 이전 윈도우 끝에 8개, 새 윈도우 시작에 10개 시도

    println("Fixed Window:")
    val fixedKey = RequestKey("compare-fixed")
    repeat(8) { fixedLimiter.tryAcquire(fixedKey, 10.per.second) }
    delay(1000)  // 새 윈도우
    repeat(10) { i ->
        val allowed = fixedLimiter.tryAcquire(fixedKey, 10.per.second)
        println("  $i: ${if (allowed) "✅" else "❌"}")
    }
    // 예상: 모두 허용! (Burst!)

    println("\nSliding Window Counter:")
    val counterKey = RequestKey("compare-counter")
    repeat(8) { counterLimiter.tryAcquire(counterKey, 10.per.second) }
    delay(1000)
    repeat(10) { i ->
        val allowed = counterLimiter.tryAcquire(counterKey, 10.per.second)
        println("  $i: ${if (allowed) "✅" else "❌"}")
        if (allowed) delay(10)
    }
    // 예상: 2-3개만 허용 (이전 윈도우 영향)

    println("\n=== Sliding Window Counter Test 4: 점진적 허용 ===")
    val gradualKey = RequestKey("gradual")

    // 이전 윈도우: 10개
    repeat(10) { limiter.tryAcquire(gradualKey, 10.per.second) }

    // 100ms씩 대기하며 시도 (점진적으로 elapsedRatio 증가)
    delay(1000)  // 새 윈도우
    repeat(15) { i ->
        delay(100)  // 10% 경과
        val allowed = limiter.tryAcquire(gradualKey, 10.per.second)
        val elapsedRatio = (i + 1) * 10
        println("  ${elapsedRatio}% 경과, Request $i: ${if (allowed) "✅" else "❌"}")
    }
    // 시간이 지날수록 이전 윈도우 영향 감소 → 점점 더 허용
}
