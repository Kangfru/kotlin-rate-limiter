package com.kangfru.kotlinratelimiter

import com.kangfru.kotlinratelimiter.algorithm.TokenBucketRateLimiter
import com.kangfru.kotlinratelimiter.domain.*
import com.kangfru.kotlinratelimiter.domain.RateLimitConfig.Companion.per
import com.kangfru.kotlinratelimiter.storage.InMemoryStorage
import kotlinx.coroutines.*

suspend fun main() = coroutineScope {
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
