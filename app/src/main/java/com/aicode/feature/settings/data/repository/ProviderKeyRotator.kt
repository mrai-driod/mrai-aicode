package com.aicode.feature.settings.data.repository

import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.KeyRotationStrategy
import com.aicode.feature.settings.domain.model.ProviderKey
import javax.inject.Inject
import javax.inject.Singleton

/** The result of one key switch, for the caller to rewrite the credential and prompt "Switched to key N". */
data class KeySwitchResult(
    val newKey: String,
    /** The 1-based index of the new key. */
    val newIndex: Int,
    val total: Int
)

/**
 * Multi-key rotator: decides which API key a provider should use on a given session, and switches to the next one when a key is unavailable.
 *
 * All state is runtime in-memory state, not persisted: after a process restart it returns to the first key and cooldown records are cleared — the point of multi-key is
 * to be able to bypass authentication/quota/rate-limit problems immediately; there is no semantics that must be preserved across processes.
 *
 * **Session stickiness** is the core constraint here: the server-side prompt cache is isolated per API key, so changing the key on every request within the same session would make
 * every round land on a key with no cache, driving the cache hit rate to zero and worsening both cost and first-byte latency. Therefore the key is only changed when a session first obtains
 * a key, or when the current key is judged unavailable; in all other cases it sticks to the same key.
 */
@Singleton
class ProviderKeyRotator @Inject constructor() {

    /** The provider key configuration recorded by the most recent [activeKey], for reading candidates and the cooldown duration when a failure is reported. */
    private data class KeySetup(
        val keys: List<ProviderKey>,
        val strategy: KeyRotationStrategy,
        val cooldownMillis: Long
    )

    private val lock = Any()

    private val setups = mutableMapOf<String, KeySetup>()

    /** providerId + key → cooldown deadline timestamp (milliseconds). */
    private val cooldownUntil = mutableMapOf<String, Long>()

    /** providerId → key value → current weight for smooth weighted round-robin (runtime in-memory state). */
    private val weightedState = mutableMapOf<String, MutableMap<String, Long>>()

    /** providerId → the most recently selected key, for bypass requests such as balance queries to obtain the "currently active key". */
    private val lastSelected = mutableMapOf<String, String>()

    /**
     * providerId + sessionId → the key this session sticks to. The number of sessions is unbounded, so an access-order LRU bounds memory;
     * an evicted old session simply gets a key reassigned on its next request, with no correctness issue.
     */
    private val sessionKeys = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_TRACKED_SESSIONS
    }

    /**
     * Get the key [config] should currently use on [sessionId]; returns null when there is no usable key, or all keys are cooling down.
     * Also snapshots this provider's key configuration for later use by [reportFailure].
     */
    fun activeKey(config: AIProviderConfig, sessionId: String?): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            setups[config.id] = KeySetup(
                keys = keys,
                strategy = config.keyRotationStrategy,
                cooldownMillis = config.keyCooldownMinutes.coerceAtLeast(0) * 60_000L
            )

            val bindKey = sessionId?.let { sessionBinding(config.id, it) }
            val bound = bindKey?.let { sessionKeys[bindKey] }
            if (bound != null && keys.any { it.value == bound } && !isCoolingDown(config.id, bound)) return bound

            val chosen = pick(config.id, keys, config.keyRotationStrategy, exclude = emptySet()) ?: return null
            if (bindKey != null) sessionKeys[bindKey] = chosen
            lastSelected[config.id] = chosen
            return chosen
        }
    }

    /**
     * Read-only retrieval of this provider's currently active key: creates no session binding and does not advance the round-robin cursor.
     * For bypass requests such as balance queries — they should reflect "which key the chat is using", without disturbing the rotation rhythm.
     */
    fun currentKey(config: AIProviderConfig): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            val last = lastSelected[config.id]
            if (last != null && keys.any { it.value == last } && !isCoolingDown(config.id, last)) return last
            return keys.firstOrNull { !isCoolingDown(config.id, it.value) }?.value ?: keys.first().value
        }
    }

    /**
     * Report a failure attributable to [key]: **immediately** switch to the next usable key, put [key] into cooldown and rebind the session,
     * and return the switch result; returns null when there is no candidate to switch to (all candidates tried, or all cooling down) — in that case [key] is **not** cooled down,
     * to avoid locking up the last usable key as well and leaving the user completely unable to make requests for a period of time.
     *
     * [triedKeys] are the keys already tried for this request (including [key] itself), used to avoid trying the same key repeatedly within one request.
     */
    fun reportFailure(
        providerId: String,
        sessionId: String?,
        key: String,
        triedKeys: Set<String> = emptySet()
    ): KeySwitchResult? {
        synchronized(lock) {
            val setup = setups[providerId] ?: return null
            if (setup.keys.none { it.value == key }) return null

            // First confirm there is a candidate to switch to; if not, do not cool down — cooling down the last usable key would only leave the user completely unable to use it for a period of time
            val next = pick(providerId, setup.keys, setup.strategy, exclude = triedKeys + key) ?: return null
            if (setup.cooldownMillis > 0) {
                cooldownUntil[stateKey(providerId, key)] = System.currentTimeMillis() + setup.cooldownMillis
            }
            sessionId?.let { sessionKeys[sessionBinding(providerId, it)] = next }
            lastSelected[providerId] = next
            val index = setup.keys.indexOfFirst { it.value == next } + 1
            FileLogger.i(TAG, "Key switched provider=$providerId ${key.masked()} → ${next.masked()} (key $index/${setup.keys.size})")
            return KeySwitchResult(newKey = next, newIndex = index, total = setup.keys.size)
        }
    }

    /**
     * Pick a selectable key by strategy: exclude [exclude] (already tried this time) and keys in cooldown; keys with weight ≤ 0 do not participate either;
     * Returns null when none is selectable. Does not fall back to "if all are cooling down, go back to the earliest expiring one and try again" — that would be forcing a request through with a key known to be unusable.
     */
    private fun pick(
        providerId: String,
        keys: List<ProviderKey>,
        strategy: KeyRotationStrategy,
        exclude: Set<String>
    ): String? {
        val participants = keys.filter {
            it.weight > 0 && it.value !in exclude && !isCoolingDown(providerId, it.value)
        }
        if (participants.isEmpty()) return null
        return when (strategy) {
            KeyRotationStrategy.SEQUENTIAL -> participants.first().value
            KeyRotationStrategy.ROUND_ROBIN -> weightedRoundRobin(providerId, participants)
        }
    }

    /**
     * Smooth weighted round-robin (SWRR): each round adds each candidate's own weight to it, takes the one with the largest current weight, then subtracts the total candidate weight from the selected one.
     * No need to expand into a virtual list by weight; weights can reach the millions; the distribution is even and does not keep landing on the same key.
     */
    private fun weightedRoundRobin(providerId: String, participants: List<ProviderKey>): String {
        val current = weightedState.getOrPut(providerId) { mutableMapOf() }
        var total = 0L
        var best = participants.first()
        var bestWeight = Long.MIN_VALUE
        for (candidate in participants) {
            val next = (current[candidate.value] ?: 0L) + candidate.weight
            current[candidate.value] = next
            total += candidate.weight
            if (next > bestWeight) {
                bestWeight = next
                best = candidate
            }
        }
        current[best.value] = (current[best.value] ?: 0L) - total
        return best.value
    }

    private fun isCoolingDown(providerId: String, key: String): Boolean {
        val until = cooldownUntil[stateKey(providerId, key)] ?: return false
        if (System.currentTimeMillis() >= until) {
            cooldownUntil.remove(stateKey(providerId, key))
            return false
        }
        return true
    }

    private fun stateKey(providerId: String, key: String) = "$providerId\u0000$key"

    private fun sessionBinding(providerId: String, sessionId: String) = "$providerId\u0000$sessionId"

    /** Log masking: keep only the last 4 characters, to avoid writing the full key into the log file. */
    private fun String.masked(): String = if (length <= 4) "****" else "****${takeLast(4)}"

    private companion object {
        const val TAG = "ProviderKeyRotator"
        const val MAX_TRACKED_SESSIONS = 200
    }
}
