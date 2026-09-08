package com.brianchen.locklist.sync

import android.content.Context
import android.util.Log
import com.brianchen.locklist.data.TaskArea
import com.brianchen.locklist.data.TaskRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class TaskSync(
    context: Context,
    private val repo: TaskRepository,
    private val supabase: SupabaseClient,
    private val appScope: CoroutineScope
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var realtimeJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _liveStatus = MutableStateFlow("Live sync off")
    val liveStatus: StateFlow<String> = _liveStatus

    @Volatile
    private var lastSyncAt = 0L

    suspend fun isSignedIn(): Boolean {
        supabase.auth.awaitInitialization()
        return supabase.auth.currentSessionOrNull() != null
    }

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(io.github.jan.supabase.auth.providers.builtin.Email) {
            this.email = email
            this.password = password
        }
        startRealtime()
    }

    suspend fun signOut() {
        stopRealtime()
        supabase.auth.signOut()
    }

    suspend fun currentEmail(): String? {
        supabase.auth.awaitInitialization()
        return supabase.auth.currentUserOrNull()?.email
    }

    /**
     * Starts the supervised live-sync loop. If it is already running but the socket is down
     * (waiting out a backoff), nudges it to retry now; a healthy socket is left alone.
     */
    fun startRealtime() {
        val running = realtimeJob
        if (running != null && running.isActive) {
            if (supabase.realtime.status.value != Realtime.Status.CONNECTED) pokeRealtime()
            return
        }
        realtimeJob = appScope.launch { realtimeLoop() }
    }

    fun stopRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
        _liveStatus.value = "Live sync off"
    }

    /** Forces the live loop to rebuild its socket now (network came back, app woke, etc.). */
    fun pokeRealtime() {
        wake.trySend(Unit)
    }

    fun onNetworkAvailable() {
        val running = realtimeJob
        if (running != null && running.isActive) {
            // A new default network usually means the old socket is dead even if the
            // library has not noticed yet; rebuild it.
            pokeRealtime()
        } else {
            startRealtime()
        }
        requestQuickSync("network available", minIntervalMs = 5_000)
    }

    /** Cheap catch-up pull, throttled so screen-on spam does not hammer the API. */
    fun requestQuickSync(reason: String, minIntervalMs: Long = 15_000) {
        if (System.currentTimeMillis() - lastSyncAt < minIntervalMs) return
        appScope.launch {
            if (isSignedIn()) safeSync(reason)
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun realtimeLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            if (!isSignedIn()) {
                _liveStatus.value = "Live sync off (not signed in)"
                return
            }
            var channel: RealtimeChannel? = null
            var dropped = false
            try {
                _liveStatus.value = if (failures == 0) "Live sync connecting…" else "Live sync reconnecting…"
                supabase.realtime.connect()
                val next = supabase.channel("$CHANNEL_PREFIX${System.currentTimeMillis()}")
                channel = next
                val changes = next.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "portal_tasks"
                }
                coroutineScope {
                    val collector = launch {
                        changes.debounce(300).collect { safeSync("realtime change") }
                    }
                    withTimeout(SUBSCRIBE_TIMEOUT_MS) { next.subscribe(blockUntilSubscribed = true) }
                    failures = 0
                    _liveStatus.value = "Live sync connected"
                    Log.i(TAG, "realtime subscribed on ${next.topic}")
                    safeSync("realtime connected")
                    // Drop pokes that piled up while we were connecting; only new ones count.
                    wake.tryReceive()
                    val reason = merge(
                        supabase.realtime.status
                            .filter { it == Realtime.Status.DISCONNECTED }
                            .map { "socket closed" },
                        next.status
                            .filter { it == RealtimeChannel.Status.UNSUBSCRIBED }
                            .map { "channel closed" },
                        wake.receiveAsFlow().map { "woken" }
                    ).first()
                    dropped = true
                    Log.w(TAG, "realtime dropped: $reason")
                    collector.cancel()
                }
            } catch (e: CancellationException) {
                teardown(channel)
                throw e
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "realtime failed (attempt $failures)", e)
            }
            teardown(channel)
            val delayMs = if (dropped && failures == 0) 2_000L else backoff(failures)
            _liveStatus.value = "Live sync reconnecting in ${delayMs / 1000}s"
            withTimeoutOrNull(delayMs) { wake.receive() }
        }
    }

    private fun backoff(failures: Int): Long {
        val step = (failures - 1).coerceIn(0, 6)
        return (5_000L shl step).coerceAtMost(300_000L)
    }

    private suspend fun teardown(channel: RealtimeChannel?) {
        withContext(NonCancellable) {
            withTimeoutOrNull(5_000) {
                try {
                    channel?.let { supabase.realtime.removeChannel(it) }
                } catch (e: Exception) {
                    Log.d(TAG, "removeChannel: ${e.message}")
                }
            }
            try {
                supabase.realtime.disconnect()
            } catch (e: Exception) {
                Log.d(TAG, "disconnect: ${e.message}")
            }
        }
    }

    private suspend fun safeSync(reason: String) {
        try {
            syncOnce()
            Log.i(TAG, "synced ($reason)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync failed ($reason)", e)
        }
    }

    suspend fun syncOnce() = mutex.withLock {
        if (!isSignedIn()) return@withLock
        val startedAt = System.currentTimeMillis()
        lastSyncAt = startedAt
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
        val personId = brianPersonId()
        val email = supabase.auth.currentUserOrNull()?.email

        val remote = supabase.from("portal_tasks").select {
            filter { eq("person_id", personId) }
        }.decodeList<PortalTask>()
        val remoteById = remote.associateBy { it.id }

        var pushed = false
        val dirty = repo.getChangedSince(lastSync)
        for (local in dirty) {
            val other = remoteById[local.id]
            if (local.deleted) {
                if (other != null) {
                    supabase.from("portal_tasks").delete {
                        filter { eq("id", local.id) }
                    }
                    pushed = true
                }
                continue
            }
            if (other != null && parseIso(other.updatedAt) > local.updatedAt) continue
            pushed = true
            if (other == null) {
                supabase.from("portal_tasks").insert(
                    PortalTaskInsert(
                        id = local.id,
                        personId = personId,
                        title = local.title,
                        notes = local.notes.ifBlank { null },
                        status = local.toStatus(),
                        sort = local.sortOrder,
                        area = TaskArea.normalize(local.area),
                        completedAt = local.completedAtIso(),
                        createdAt = toIso(local.createdAt),
                        updatedAt = toIso(local.updatedAt),
                        updatedBy = email
                    )
                )
            } else {
                supabase.from("portal_tasks").update(
                    PortalTaskPatch(
                        title = local.title,
                        notes = local.notes.ifBlank { null },
                        status = local.toStatus(),
                        sort = local.sortOrder,
                        area = TaskArea.normalize(local.area),
                        completedAt = local.completedAtIso(),
                        updatedAt = toIso(local.updatedAt),
                        updatedBy = email
                    )
                ) {
                    filter { eq("id", local.id) }
                }
            }
        }

        val latestRemote = if (pushed) {
            supabase.from("portal_tasks").select {
                filter { eq("person_id", personId) }
            }.decodeList<PortalTask>()
        } else {
            remote
        }
        val latestIds = latestRemote.map { it.id }.toSet()
        val localById = repo.getAll().associateBy { it.id }
        latestRemote.forEach { row ->
            val local = localById[row.id]
            if (local == null || parseIso(row.updatedAt) >= local.updatedAt) {
                repo.applyRemote(row.toLocal())
            }
        }

        if (lastSync > 0L) {
            localById.values
                .filter { !it.deleted && it.id !in latestIds && it.updatedAt <= lastSync }
                .forEach { leftover ->
                    repo.applyRemote(
                        leftover.copy(deleted = true, updatedAt = startedAt)
                    )
                }
        }

        prefs.edit().putLong(KEY_LAST_SYNC, startedAt).apply()
        lastSyncAt = System.currentTimeMillis()
    }

    private suspend fun brianPersonId(): String {
        val people = supabase.from("portal_task_people").select()
            .decodeList<PortalPerson>()
        return people.firstOrNull { it.name.equals("Brian", ignoreCase = true) }?.id
            ?: error("Brian column missing on the portal task board")
    }

    companion object {
        private const val TAG = "LockList"
        private const val PREFS = "locklist_sync"
        private const val KEY_LAST_SYNC = "lastSync"
        private const val CHANNEL_PREFIX = "locklist-portal-tasks-"
        private const val SUBSCRIBE_TIMEOUT_MS = 20_000L
    }
}
