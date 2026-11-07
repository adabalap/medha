package com.adabala.medha.net

/**
 * Per-key token bucket.
 *
 * ## Why the notification API needs one
 *
 * `POST /notify` is the first endpoint where a client can make the *device*
 * do something the user did not ask for, and it is cheap to call. A page with
 * `setInterval(post, 50)` — a bug as easily as malice — would push a hundred
 * cards a second into the shade and there is no in-app way to make it stop
 * short of revoking the token. Every other endpoint is self-limiting because
 * inference is slow and the scheduler queues it; this one is not.
 *
 * A bucket rather than a fixed window because the useful shape is "a burst is
 * fine, a sustained flood is not": a client legitimately posts three cards at
 * once when it syncs, then nothing for an hour.
 *
 * Pure Kotlin with an injectable clock so the refill maths can actually be
 * tested rather than asserted. See tools/tests/RateLimiterTest.kt.
 *
 * Thread-safe: `allow` is called from request handlers on arbitrary threads.
 */
class RateLimiter(
    private val capacity: Int,
    private val refillPerMinute: Int,
    private val maxKeys: Int = 256,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(refillPerMinute > 0) { "refillPerMinute must be positive" }
    }

    private class Bucket(var tokens: Double, var lastRefillAt: Long)

    private val lock = Any()
    private val buckets = LinkedHashMap<String, Bucket>()

    /**
     * Consumes one token for [key], returning false if the bucket is empty.
     */
    fun allow(key: String): Boolean = synchronized(lock) {
        val now = clock()
        val b = buckets[key] ?: Bucket(capacity.toDouble(), now).also {
            // Bound the map: a caller could otherwise mint unbounded keys and
            // turn a defence into a memory leak. Eviction order is insertion,
            // and an evicted key simply starts full again — acceptable,
            // because evicting requires 256 other active keys, which is far
            // more clients than this device will ever have.
            if (buckets.size >= maxKeys) {
                val oldest = buckets.keys.firstOrNull()
                if (oldest != null) buckets.remove(oldest)
            }
            buckets[key] = it
        }

        val elapsedMs = now - b.lastRefillAt
        if (elapsedMs > 0) {
            b.tokens = minOf(
                capacity.toDouble(),
                b.tokens + (elapsedMs / 60_000.0) * refillPerMinute
            )
            b.lastRefillAt = now
        }

        return if (b.tokens >= 1.0) {
            b.tokens -= 1.0
            true
        } else {
            false
        }
    }

    /**
     * Whole seconds until [key] has a token again, for a `Retry-After`
     * header. Rounded up, and never below 1 for a key that is out, because
     * `Retry-After: 0` invites an immediate retry that will also fail.
     */
    fun retryAfterSeconds(key: String): Long = synchronized(lock) {
        val b = buckets[key] ?: return 0
        if (b.tokens >= 1.0) return 0
        val needed = 1.0 - b.tokens
        val ms = needed / refillPerMinute * 60_000.0
        return maxOf(1L, Math.ceil(ms / 1000.0).toLong())
    }

    /** Drops [key]'s bucket. Used when a client is revoked. */
    fun forget(key: String) = synchronized(lock) { buckets.remove(key); Unit }
}
