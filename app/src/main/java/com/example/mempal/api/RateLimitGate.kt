package com.example.mempal.api

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Tracks HTTP 429 responses per host and pauses requests until the backoff expires.
 *
 * The gate does not cache data. It only prevents new requests from being sent while
 * the server has explicitly told us to slow down.
 */
object RateLimitGate {
    private const val TAG = "RateLimitGate"
    private const val DEFAULT_BACKOFF_MS = 60_000L
    private const val MIN_BACKOFF_MS = 10_000L
    private const val MAX_BACKOFF_MS = 15 * 60_000L
    private const val MAX_STRIKES = 5

    private class HostState {
        var blockedUntil: Long = 0L
        var strikes: Int = 0
    }

    private val hosts = ConcurrentHashMap<String, HostState>()

    fun isBlocked(host: String): Boolean = remainingMs(host) > 0L

    /** True when any host is backing off. Used by polling schedulers to pause work. */
    fun isAnyBlocked(): Boolean {
        val now = System.currentTimeMillis()
        return hosts.values.any { it.blockedUntil > now }
    }

    fun remainingMs(host: String): Long {
        val state = hosts[host] ?: return 0L
        return (state.blockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun maxRemainingMs(): Long {
        val now = System.currentTimeMillis()
        return hosts.values.maxOfOrNull { (it.blockedUntil - now).coerceAtLeast(0L) } ?: 0L
    }

    fun onRateLimited(host: String, retryAfterMs: Long?): Long {
        val state = hosts.computeIfAbsent(host) { HostState() }
        synchronized(state) {
            state.strikes = (state.strikes + 1).coerceAtMost(MAX_STRIKES)
            val exponential = DEFAULT_BACKOFF_MS * (1L shl (state.strikes - 1))
            val backoff = (retryAfterMs ?: exponential)
                .coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
            state.blockedUntil = System.currentTimeMillis() + backoff
            Log.w(TAG, "Rate limited by $host for ${backoff / 1000}s (strike ${state.strikes})")
            return backoff
        }
    }

    fun onSuccess(host: String) {
        val state = hosts[host] ?: return
        synchronized(state) {
            state.strikes = 0
            state.blockedUntil = 0L
        }
    }

    internal fun parseRetryAfterMs(response: Response): Long? {
        val value = response.header("Retry-After")?.trim() ?: return null
        // HTTP-date values are rare here; fall back to the default backoff for those.
        return value.toLongOrNull()?.let { seconds ->
            (seconds * 1000L).coerceAtLeast(0L)
        }
    }
}

/**
 * Fails fast while a host is in backoff and records 429 responses.
 * Intentionally has no retry logic: returning the 429 lets callers stop cleanly.
 */
class RateLimitInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        if (RateLimitGate.isBlocked(host)) {
            return rateLimitedResponse(request, RateLimitGate.remainingMs(host))
        }

        val response = chain.proceed(request)

        if (response.code == 429) {
            val retryAfterMs = RateLimitGate.parseRetryAfterMs(response)
            RateLimitGate.onRateLimited(host, retryAfterMs)
            response.close()
            return rateLimitedResponse(request, RateLimitGate.remainingMs(host))
        }

        if (response.isSuccessful) {
            RateLimitGate.onSuccess(host)
        }

        return response
    }

    private fun rateLimitedResponse(request: Request, retryAfterMs: Long): Response {
        val retryAfterSeconds = ceil(retryAfterMs / 1000.0).toLong()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Rate limited")
            .header("Retry-After", retryAfterSeconds.toString())
            .body("".toResponseBody(null))
            .build()
    }
}
