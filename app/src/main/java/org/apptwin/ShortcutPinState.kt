package org.apptwin

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import java.util.UUID

internal data class ShortcutPinLease(
    val issuedAtEpochMillis: Long,
    val activeUntilEpochMillis: Long,
    val nonce: String,
)

internal interface ShortcutPinLeaseStore {
    fun entries(): Map<String, ShortcutPinLease>
    fun put(shortcutId: String, lease: ShortcutPinLease)
    fun remove(shortcutIds: Set<String>)
}

private class SharedPreferencesShortcutPinLeaseStore(
    private val preferences: SharedPreferences,
) : ShortcutPinLeaseStore {
    override fun entries(): Map<String, ShortcutPinLease> {
        val decoded = linkedMapOf<String, ShortcutPinLease>()
        val invalid = linkedSetOf<String>()
        preferences.all.forEach { (key, value) ->
            val lease = (value as? String)?.toLease()
            if (lease == null) invalid += key else decoded[key] = lease
        }
        remove(invalid)
        return decoded
    }

    override fun put(shortcutId: String, lease: ShortcutPinLease) {
        preferences.edit().putString(shortcutId, lease.encode()).apply()
    }

    override fun remove(shortcutIds: Set<String>) {
        if (shortcutIds.isEmpty()) return
        preferences.edit().also { editor ->
            shortcutIds.forEach(editor::remove)
        }.apply()
    }

    private fun ShortcutPinLease.encode(): String =
        "$issuedAtEpochMillis|$activeUntilEpochMillis|$nonce"

    private fun String.toLease(): ShortcutPinLease? {
        val parts = split('|', limit = 3)
        if (parts.size != 3) return null
        return ShortcutPinLease(
            issuedAtEpochMillis = parts[0].toLongOrNull() ?: return null,
            activeUntilEpochMillis = parts[1].toLongOrNull() ?: return null,
            nonce = parts[2].takeIf(String::isNotBlank) ?: return null,
        )
    }
}

/**
 * Prevents resume-time shortcut reconciliation from rewriting an item while the launcher is
 * accepting and placing it. The request lease is durable so the callback remains useful if the
 * app process is recreated behind the launcher's confirmation UI.
 */
internal class ShortcutPinState(
    private val store: ShortcutPinLeaseStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val requestTtlMillis: Long = REQUEST_TTL_MILLIS,
    private val confirmedGraceMillis: Long = CONFIRMED_GRACE_MILLIS,
    private val nonceFactory: () -> String = { UUID.randomUUID().toString() },
    private val maxPendingRequests: Int = MAX_PENDING_REQUESTS,
) {
    init {
        require(requestTtlMillis > 0)
        require(confirmedGraceMillis in 1..requestTtlMillis)
        require(maxPendingRequests > 0)
    }

    constructor(context: Context) : this(
        SharedPreferencesShortcutPinLeaseStore(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
    )

    fun markRequested(shortcutId: String): String = synchronized(LOCK) {
        require(shortcutId.isNotBlank())
        val now = nowEpochMillis()
        val active = activeEntriesLocked(now)
        val addedEntryCount = if (shortcutId in active) 0 else 1
        val overflow = active.asSequence()
            .filterNot { (id, _) -> id == shortcutId }
            .sortedBy { (_, lease) -> lease.issuedAtEpochMillis }
            .take((active.size + addedEntryCount - maxPendingRequests).coerceAtLeast(0))
            .mapTo(linkedSetOf()) { (id, _) -> id }
        store.remove(overflow)
        val nonce = nonceFactory().also { require(it.isNotBlank()) }
        store.put(
            shortcutId,
            ShortcutPinLease(
                issuedAtEpochMillis = now,
                activeUntilEpochMillis = now + requestTtlMillis,
                nonce = nonce,
            ),
        )
        nonce
    }

    fun markConfirmed(shortcutId: String, nonce: String): Boolean = synchronized(LOCK) {
        if (shortcutId.isBlank() || nonce.isBlank()) return@synchronized false
        val now = nowEpochMillis()
        val requested = activeEntriesLocked(now)[shortcutId]
            ?.takeIf { lease -> lease.nonce == nonce }
            ?: return@synchronized false
        store.put(
            shortcutId,
            requested.copy(
                issuedAtEpochMillis = now,
                activeUntilEpochMillis = now + confirmedGraceMillis,
            ),
        )
        true
    }

    fun cancel(shortcutId: String, nonce: String) = synchronized(LOCK) {
        val current = store.entries()[shortcutId]
        if (current?.nonce == nonce) store.remove(setOf(shortcutId))
    }

    fun pendingIds(): Set<String> = synchronized(LOCK) {
        activeEntriesLocked(nowEpochMillis()).keys
    }

    private fun activeEntriesLocked(now: Long): Map<String, ShortcutPinLease> {
        val entries = store.entries()
        val invalid = entries.filterNot { (id, lease) ->
            id.isNotBlank() &&
                lease.nonce.isNotBlank() &&
                lease.issuedAtEpochMillis <= now &&
                lease.activeUntilEpochMillis > now &&
                lease.activeUntilEpochMillis - lease.issuedAtEpochMillis in 1..requestTtlMillis &&
                lease.activeUntilEpochMillis <= now + requestTtlMillis
        }.keys
        store.remove(invalid)
        return entries - invalid
    }

    private companion object {
        val LOCK = Any()
        const val PREFERENCES_NAME = "shortcut_pin_state"
        const val REQUEST_TTL_MILLIS = 5 * 60 * 1_000L
        const val CONFIRMED_GRACE_MILLIS = 5_000L
        const val MAX_PENDING_REQUESTS = 32
    }
}

/** Receives the platform callback only after the launcher has accepted a pin request. */
class ShortcutPinResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PIN_CONFIRMED) return
        val shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID)?.takeIf(String::isNotBlank)
            ?: return
        val nonce = intent.getStringExtra(EXTRA_NONCE)?.takeIf(String::isNotBlank) ?: return
        ShortcutPinState(context.applicationContext).markConfirmed(shortcutId, nonce)
    }

    companion object {
        private const val ACTION_PIN_CONFIRMED = "org.apptwin.action.SHORTCUT_PIN_CONFIRMED"
        private const val EXTRA_SHORTCUT_ID = "org.apptwin.extra.SHORTCUT_ID"
        private const val EXTRA_NONCE = "org.apptwin.extra.SHORTCUT_PIN_NONCE"

        internal fun intentSender(context: Context, shortcutId: String, nonce: String) =
            PendingIntent.getBroadcast(
                context,
                nonce.hashCode(),
                Intent(context, ShortcutPinResultReceiver::class.java)
                    .setAction(ACTION_PIN_CONFIRMED)
                    .setData(
                        Uri.parse(
                            "apptwin-shortcut://pin/${Uri.encode(shortcutId)}/${Uri.encode(nonce)}",
                        ),
                    )
                    .putExtra(EXTRA_SHORTCUT_ID, shortcutId)
                    .putExtra(EXTRA_NONCE, nonce),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            ).intentSender
    }
}
