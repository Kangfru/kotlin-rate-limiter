package com.kangfru.kotlinratelimiter.domain

@JvmInline
value class RequestKey(val value: String) {

    init {
        require(value.isNotBlank()) { "Request key cannot be blank" }
    }

}