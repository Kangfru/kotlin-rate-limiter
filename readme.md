# kotlin-rate-limiter
## 개요
Kotlin 기반의 Rate Limiter 학습용 라이브러리로, Token Bucket 알고리즘을 활용한 요청 제어(Rate Limiting) 기능을 제공한다.

학습 목표는 Kotlin 중급~고급 기능 활용과 Rate Limiter 알고리즘에 대한 이해를 목표로 한다.

## Rate Limiter란?

Rate Limiter는 특정 시간 동안 허용되는 요청의 수를 제한하는 메커니즘이다. API 서버의 과부하 방지, DDoS 공격 차단, 외부 API 호출 시 rate limit 준수 등의 목적으로 사용된다.

### 사용 시나리오

**Server-side Rate Limiting (API 제공자)**
- 사용자별/IP별로 API 요청 수 제한
- 분당 100회, 시간당 1000회 등의 정책 적용
- API abuse 방지 및 서버 보호

**Client-side Rate Limiting (API 소비자)**
- 외부 API 호출 시 상대방의 rate limit 준수
- 429 Too Many Requests 에러 방지
- 배치 작업에서 대량 API 호출 시 속도 조절

## Token Bucket 알고리즘

Token Bucket은 Rate Limiting을 구현하는 대표적인 알고리즘 중 하나로, 버킷(Bucket)에 토큰(Token)을 담아두고 요청마다 토큰을 소비하는 방식이다.

### 핵심 개념

```
┌─────────────────┐
│   Token Bucket  │  Capacity: 100 tokens
│                 │
│  🪙🪙🪙🪙🪙    │  Current: 85 tokens
│  🪙🪙🪙🪙🪙    │
│  ...            │
└─────────────────┘
     ↓ refill (시간당 100개)
     ↓ consume (요청당 1개)
```

**구성 요소:**
- **Bucket (버킷)**: 토큰을 담는 컨테이너, 최대 용량(capacity) 존재
- **Token (토큰)**: 요청 권한을 나타내는 단위, 1 요청 = 1 토큰 소비
- **Refill Rate (리필 속도)**: 시간당 버킷에 추가되는 토큰의 수
- **Last Refill Time (마지막 리필 시간)**: 토큰이 마지막으로 충전된 시각

### 동작 원리

```kotlin
// 설정: 100 requests per minute~~~~
// Capacity: 100 tokens
// Refill rate: 100 tokens / 60 seconds = 1.67 tokens/second

// Timeline:
// 00:00 - 버킷 생성, 토큰 100개
// 00:01 - 요청 50개 → 토큰 50개 소비 → 남은 토큰: 50
// 00:02 - 1초 경과 → 1.67개 리필 → 남은 토큰: 51
// 00:03 - 요청 60개 → 51개만 허용, 9개 거부 → 남은 토큰: 0
// 00:04 - 1초 경과 → 1.67개 리필 → 남은 토큰: 1
// 01:00 - 60초 경과 → 100개 리필 → 남은 토큰: 100 (max)
```

### 수학적 계산

Token Bucket 알고리즘의 핵심은 경과 시간에 따른 토큰 리필 계산이다.

```kotlin
// 1. Refill Rate (초당 토큰 수)
refillRate = limit / window(초)

// 예: 100 per minute
refillRate = 100 / 60 = 1.666... tokens/second

// 2. 경과 시간 (초)
elapsed = Duration.between(lastRefillTime, now).toMillis() / 1000.0

// 3. 리필할 토큰 수
tokensToAdd = elapsed * refillRate

// 4. 현재 토큰 (오버플로우 방지)
currentTokens = min(previousTokens + tokensToAdd, capacity)

// 5. 토큰 소비 가능 여부
if (currentTokens >= 1.0) {
    newTokens = currentTokens - 1.0
    허용
} else {
    거부
}
```

### 구현 예시

```kotlin
val storage = InMemoryStorage()
val limiter = TokenBucketRateLimiter(storage)

// 분당 100회 제한
val result = limiter.execute(
    key = RequestKey("user:123"),
    config = 100.per.minute
) {
    externalApi.fetchData()
}

when (result) {
    is RateLimitResult.Allowed -> {
        // 요청 허용됨
        println("Remaining: ${result.remaining}")
        println("Reset at: ${result.resetAt}")
    }
    is RateLimitResult.Denied -> {
        // 요청 거부됨
        println("Retry after: ${result.retryAfter}")
    }
    is RateLimitResult.Error -> {
        // 에러 발생
        println("Error: ${result.cause}")
    }
}
```

### Token Bucket vs 다른 알고리즘

**Fixed Window Counter**
- 고정된 시간 윈도우(예: 매 분의 00초~59초)에서 카운터 증가
- 단점: 윈도우 경계에서 burst traffic 발생 가능
- 예: 00:59에 100회, 01:00에 100회 → 1초 만에 200회

**Sliding Window Log**
- 각 요청의 타임스탬프를 로그로 저장
- 장점: 정확한 rate limiting
- 단점: 메모리 사용량이 많음 (모든 요청 기록)

**Token Bucket (본 프로젝트)**
- 장점: 메모리 효율적 (토큰 수와 시간만 저장)
- 장점: 일시적인 burst 허용 (버킷에 토큰이 쌓여있으면)
- 장점: 구현이 비교적 간단
- 단점: Fixed Window처럼 완전히 균일한 분산은 아님

## 아키텍처

```
┌─────────────────────────────────────┐
│         Application Code            │
│  limiter.execute(key, config) { }   │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│         RateLimiter Interface       │
│  - execute()                        │
│  - tryAcquire()                     │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│    TokenBucketRateLimiter           │
│  (알고리즘 로직)                     │
│  - 토큰 계산                         │
│  - 리필 로직                         │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│     RateLimitStorage Interface      │
│  - get(key)                         │
│  - save(key, state)                 │
└──────────────┬──────────────────────┘
               │
      ┌────────┴────────┐
      ▼                 ▼
┌──────────┐      ┌──────────┐
│ InMemory │      │  Redis   │
│ Storage  │      │ Storage  │
└──────────┘      └──────────┘
```

### 계층별 역할

**RateLimiter (Interface)**
- 클라이언트가 사용하는 최상위 인터페이스
- `execute()`: 람다 블록 실행과 rate limiting을 함께 처리
- `tryAcquire()`: 단순히 토큰 획득 가능 여부만 확인

**TokenBucketRateLimiter (Implementation)**
- Token Bucket 알고리즘의 실제 구현체
- 경과 시간 계산, 토큰 리필, 소비 로직 포함
- Storage를 활용하여 상태 저장/조회

**RateLimitStorage (Interface)**
- 토큰 버킷 상태를 저장하는 저장소 추상화
- 다양한 구현체로 교체 가능 (In-memory, Redis 등)

**InMemoryStorage (Implementation)**
- `ConcurrentHashMap` 기반 메모리 저장소
- 키별 독립적인 `Mutex`로 동시성 제어
- 단일 인스턴스 환경에 적합

## 동시성 제어

Rate Limiter는 여러 스레드/코루틴에서 동시에 접근할 수 있으므로 동시성 제어가 필수적이다.

### Mutex를 활용한 키별 잠금

```kotlin
class InMemoryStorage : RateLimitStorage {
    private val storage = ConcurrentHashMap<String, TokenBucketState>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    
    override suspend fun save(key: String, state: TokenBucketState) {
        val mutex = locks.getOrPut(key) { Mutex() }
        mutex.withLock {
            storage[key] = state
        }
    }
}
```

**ConcurrentHashMap vs Mutex**
- `ConcurrentHashMap`: 맵 자체의 thread-safety 보장
- `Mutex`: 특정 키에 대한 read → calculate → write 원자성 보장

**키별 독립적 잠금의 장점**
- `user:123`의 rate limit 체크가 `user:456`의 체크를 블로킹하지 않음
- 전역 락(Global Lock)보다 훨씬 높은 처리량 달성
- 다른 키는 동시에 처리 가능하여 성능 최적화

### Mutex vs synchronized

**Java synchronized의 한계**
```kotlin
// ❌ 코루틴에서 synchronized 사용 시 문제
suspend fun save() {
    synchronized(lock) {  // 스레드를 블로킹!
        storage[key] = value
    }
}
```
- `synchronized`는 스레드 전체를 블로킹
- 코루틴의 경량 동시성 이점을 상실
- 스레드 풀의 스레드가 낭비됨

**Kotlin Mutex의 해결**
```kotlin
// ✅ 코루틴 친화적
suspend fun save() {
    mutex.withLock {  // 코루틴만 suspend
        storage[key] = value
    }
}
```
- 코루틴만 일시 중단, 스레드는 다른 코루틴 실행 가능
- Non-blocking 방식으로 동작
- 훨씬 높은 동시성 달성

## 사용 예제

### 기본 사용법

```kotlin
val storage = InMemoryStorage()
val limiter = TokenBucketRateLimiter(storage)

// 사용자별 rate limiting
val result = limiter.execute(
    key = RequestKey("user:${userId}"),
    config = 100.per.minute
) {
    userService.processRequest(userId)
}
```

### Extension Function을 활용한 함수형 스타일

```kotlin
limiter.execute(RequestKey("api:payment"), 10.per.minute) {
    paymentService.process()
}
    .onAllowed { result ->
        logger.info("Payment processed: $result")
    }
    .onDenied { retryAfter ->
        logger.warn("Rate limited, retry after ${retryAfter.seconds}s")
    }
    .onError { error ->
        logger.error("Payment error", error)
    }
```

### Spring Boot에서의 활용 (Interceptor)

```kotlin
@Component
class RateLimitInterceptor(
    private val limiter: RateLimiter
) : HandlerInterceptor {
    
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val userId = request.getHeader("X-User-Id")
        val allowed = runBlocking {
            limiter.tryAcquire(
                RequestKey("user:$userId"),
                100.per.minute
            )
        }
        
        if (!allowed) {
            response.status = 429  // Too Many Requests
            response.addHeader("Retry-After", "60")
            return false
        }
        
        return true
    }
}
```

## 테스트

```kotlin
@Test
fun `should allow requests within limit`() = runTest {
    val limiter = TokenBucketRateLimiter(InMemoryStorage())
    
    // 3 per second 설정, 3개까지 허용
    repeat(3) {
        val allowed = limiter.tryAcquire(RequestKey("test"), 3.per.second)
        assertTrue(allowed)
    }
    
    // 4번째는 거부
    val denied = limiter.tryAcquire(RequestKey("test"), 3.per.second)
    assertFalse(denied)
}

@Test
fun `should refill tokens after time elapsed`() = runTest {
    val limiter = TokenBucketRateLimiter(InMemoryStorage())
    
    // 토큰 소진
    limiter.tryAcquire(RequestKey("test"), 1.per.second)
    
    // 1초 대기 (1개 리필)
    delay(1100)
    
    // 다시 허용되어야 함
    val allowed = limiter.tryAcquire(RequestKey("test"), 1.per.second)
    assertTrue(allowed)
}
```

## kotlin 기능들
### Inline Value Classes
inline class는 내부에 프로퍼티를 하나만 선언한 클래스. 의미의 명확성 등의 이유로 Wrapper 클래스를 사용할 때 대체재로 Inline Value Class를 사용할 수 있다.
기존처럼 Wrapper 클래스를 사용하면, 매번 새로운 인스턴스를 생성해 Heap space를 낭비한다. JVM 은 런타임에 primitive 타입에 많은 최적화를 적용하는데 wrapper 로 감싸게 되면 그 이점을 잃는 단점이 있다.
```kotlin
@JvmInline
value class RateLimitCount(val count: Int)
// Runtime 시에 Int와 같이 처리 되지만 코드상으로 RateLimitCount의 의미를 갖게 됨과 동시에 Compile 타임의 타입 안정성도 잡을 수 있다.
// Compile 전
someRateLimitAlgorithm(RateLimitCount(10))

// Compile 후 (Runtime
someRateLimitAlgorithm(10)
```

### 확장 프로퍼티와 DSL (Companion Object)
Kotlin의 확장 프로퍼티와 `companion object`를 활용하면 외부 라이브러리(Java의 `Duration` 등)나 기본 타입(`Int`, `Long`)을 확장하여 읽기 좋은 DSL(Domain Specific Language) 형태의 API를 제공할 수 있다.

단순히 생성자를 호출하는 것보다 `10.per.second`와 같이 영어에 가까운 표현을 사용하여 설정의 가독성을 극대화한다.

```kotlin
data class RateLimitConfig(val limit: Long, val window: Duration) {
    companion object {
        // Int와 Long 타입에 확장 프로퍼티를 추가하여 Builder로 연결
        val Int.per: ConfigBuilder
            get() = ConfigBuilder(this.toLong())
    }
}

// 사용 시점: 생성자 호출보다 의도가 훨씬 명확하게 드러난다.
val config = 100.per.minute
// 생성자 호출의 경우 -> RateLimitConfig(10, Duration.ofSeconds(1))

// 단계별 변환
100             // Int: 100
    .per        // ConfigBuilder(limit=100)
        .minute // RateLimitConfig(limit=100, window=PT1M)

// 결과
RateLimitConfig(
    limit = 100,
    window = Duration.ofMinutes(1)
)
```

### Sealed Interface
Sealed Interface는 Kotlin에서 사용되는 특별한 클래스 계층 구조를 정의하는 기능으로, 상속 가능한 클래스나 인터페이스를 제한하여 특정 패키지 내에서만 사용되도록 제한할 수 있다.
Sealed class의 직접적인 하위 클래스들은 반드시 컴파일 시점에 알려져야 한다. -> Exhaustive When : when 식에서 모든 케이스의 처리를 강제하며 else 분기의 사용이 불가하다.

### Covariance(공변성) and Contravariance(반공변성)
Covariance(공변성) : T’가 T의 서브타입이면, Collection<T’>는 Collection<out T>의 서브타입이다.
- 자기 자신과 자식 객체를 허용한다. Java에서의 <? extends T>와 같다.
Contravariance(반공변성) : T’가 T의 서브타입이면, Collection<T>는 Collection<in T’>의 서브타입이다.
- 자기 자신과 부모 객체만 허용한다. Java에서의 <? super T>와 같다. Kotlin에서는 in 키워드를 사용해서 표현한다.
```kotlin
interface Player<T> {
    fun get(): T
}
class MusicPlayer<T>(val item: T) : Player<T> {
    override fun get(): T = item
}
open class Instrument
open class Guitar : Instrument()
class ElectricGuitar : Guitar()

fun playing(player: Player<out Guitar>) {
    println("Playing guitar")
}
fun ancestor(player: Player<in Guitar>) {
    
}
fun main() {
    val instrument = MusicPlayer(Instrument())
    val guitar = MusicPlayer(Guitar())
    val electricGuitar = MusicPlayer(ElectricGuitar())
    // ... 인스턴스 생성 생략
    playing(instrument) // -> 공변성 위반 : compile error
    playing(guitar)
    playing(electricGuitar)
    //
    ancestor(instrument)
    ancestor(guitar)
    ancestor(electricGuitar) // -> 반공변성 위반 : compile error
}
```
> Java에서 PECS(Producer Extends, Consumer Super) 원칙
> Producer(생산자) - Extends - out: 데이터를 꺼내올(읽을, Read) 때만 사용
> Consumer(소비자) - Super - in: 데이터를 넣을(쓸, Write) 때만 사용
> ```java
> // in java.util.Collections
> public static <T> void copy(List<? super T> dest,
> List<? extends T> src);
>```
> dest -> Consumer<br />
> src -> Producer
> 