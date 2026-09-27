package com.brianchen.locklist.sync

import android.content.Context
import android.util.Log
import com.brianchen.locklist.data.Task
import com.brianchen.locklist.data.TaskArea
import com.brianchen.locklist.data.TaskRepository
import com.brianchen.locklist.sync.SyncPlan.withContent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * lastOkAt: phone time of the last completed pass, 0 = never. pending: rows not yet on the web.
 * offline: every problem behind lastError was a missing connection, not a refusal by the web.
 */
data class SyncHealth(
    val lastOkAt: Long,
    val lastError: String?,
    val pending: Int,
    val offline: Boolean = false
)

class TaskSync(
    context: Context,
    private val repo: TaskRepository,
    private val supabase: SupabaseClient,
    private val appScope: CoroutineScope,
    private val images: TaskImages
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var realtimeJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pushRequests = Channel<Unit>(Channel.CONFLATED)
    private val json = Json { ignoreUnknownKeys = true }
    private val _liveStatus = MutableStateFlow("Live sync off")
    val liveStatus: StateFlow<String> = _liveStatus
    // Before the first pass of this version, the old watermark is the best "last synced" time.
    private val _health = MutableStateFlow(
        SyncHealth(prefs.getLong(KEY_LAST_OK, prefs.getLong(KEY_OLD_LAST_SYNC, 0L)), null, 0)
    )
    val health: StateFlow<SyncHealth> = _health.asStateFlow()

    @Volatile
    private var lastSyncAt = 0L

    init {
        appScope.launch {
            try {
                repo.observePendingCount().collect { count ->
                    _health.update { it.copy(pending = count) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pending count unavailable", e)
            }
        }
        appScope.launch { pushLoop() }
    }

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

    /** Unthrottled pass for a "Sync now" button; also wakes the live socket if it is down. */
    fun syncNow() {
        startRealtime()
        appScope.launch {
            if (isSignedIn()) safeSync("sync now")
        }
    }

    /** Called on every local edit: pushes shortly after the last of a burst of edits. */
    fun requestPush() {
        pushRequests.trySend(Unit)
    }

    /** Keeps the error visible for failures outside a pass (e.g. the background worker). */
    internal fun recordFailure(e: Throwable) {
        _health.update { it.copy(lastError = describe(e), offline = isConnectionError(e)) }
    }

    @OptIn(FlowPreview::class)
    private suspend fun pushLoop() {
        pushRequests.receiveAsFlow().debounce(PUSH_DEBOUNCE_MS).collect {
            try {
                if (isSignedIn()) safeSync("local change")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "push request failed", e)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun realtimeLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            if (!isSignedIn()) {
                // The session can read as missing for a moment around wake-ups; wait for it
                // instead of ending the loop. Only stopRealtime()/signOut() end it.
                _liveStatus.value = "Live sync off (not signed in)"
                supabase.auth.sessionStatus.first { it is SessionStatus.Authenticated }
                failures = 0
                delay(250)
                continue
            }
            var channel: RealtimeChannel? = null
            var dropped = false
            try {
                _liveStatus.value = if (failures == 0) "Live sync connecting…" else "Live sync reconnecting…"
                supabase.realtime.connect()
                val next = supabase.channel("$CHANNEL_PREFIX${System.currentTimeMillis()}")
                channel = next
                val changes = next.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = TABLE
                }
                coroutineScope {
                    val collector = launch {
                        changes.debounce(300).collect { safeSync("realtime change") }
                    }
                    // withTimeout would throw a CancellationException and end the loop for good.
                    withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) { next.subscribe(blockUntilSubscribed = true) }
                        ?: throw IOException("live sync did not subscribe in time")
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
                // Only stopRealtime()/signOut() end the loop. A cancellation from inside while
                // this job is still active (a library timeout) is just a failed attempt.
                if (!currentCoroutineContext().isActive) {
                    teardown(channel)
                    throw e
                }
                failures++
                Log.w(TAG, "realtime attempt cancelled (attempt $failures)", e)
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

    /**
     * One full pass: push every dirty row (each on its own, failures collected), then pull.
     * Throws only when the pass could not run at all (no connection, no owner column); that
     * failure is already recorded in [health].
     */
    suspend fun syncOnce() {
        mutex.withLock {
            if (!isSignedIn()) return
            lastSyncAt = System.currentTimeMillis()
            try {
                val problems = runPass()
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_OK, now).apply()
                _health.update {
                    it.copy(
                        lastOkAt = now,
                        lastError = summarize(problems),
                        offline = problems.isNotEmpty() && problems.all { p -> p.connection }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordFailure(e)
                throw e
            } finally {
                lastSyncAt = System.currentTimeMillis()
            }
        }
    }

    /** One thing that went wrong in a pass; [connection] = only the network was missing. */
    private class Problem(val text: String, val connection: Boolean = false)

    private class Pass(val ownerId: String, val email: String?, val unsafe: Boolean) {
        var wrote = false
        val problems = mutableListOf<Problem>()

        fun problem(text: String, connection: Boolean = false) {
            problems += Problem(text, connection)
        }
    }

    /** The owner's server rows. Rows that could not be read still count as present. */
    private class RemoteSnapshot(
        val rows: Map<String, PortalTask>,
        val unreadableIds: Set<String>,
        val anonymous: Int,
        val complete: Boolean
    ) {
        val isEmpty: Boolean
            get() = rows.isEmpty() && unreadableIds.isEmpty() && anonymous == 0

        fun has(id: String): Boolean = id in rows || id in unreadableIds

        // Local deletes are skipped when the list may be wrong or partial: a row we could not
        // identify, a paging gap, or an empty list while the phone holds synced tasks
        // (typically a wrong owner id).
        fun unsafeFor(syncedLocalRows: Int): Boolean =
            anonymous > 0 || !complete || (isEmpty && syncedLocalRows >= MIN_ROWS_FOR_EMPTY_CHECK)
    }

    private suspend fun runPass(): List<Problem> {
        val email = supabase.auth.currentUserOrNull()?.email
        val stored = prefs.getString(KEY_OWNER, null)
        var ownerLookedUp = stored == null
        var ownerId: String = stored ?: lookUpOwner()
        var remote = fetchRemote(ownerId)
        if (!ownerLookedUp && remote.isEmpty && !personExists(ownerId)) {
            // The pinned column was deleted on the web; find it again by name.
            ownerId = lookUpOwner()
            ownerLookedUp = true
            remote = fetchRemote(ownerId)
        }
        val syncedBefore = repo.getAll().count { !it.deleted && it.syncedAt > 0L }
        val pass = Pass(ownerId, email, ownerLookedUp || remote.unsafeFor(syncedBefore))

        val touched = mutableSetOf<String>()
        for (local in repo.getDirty()) {
            touched += local.id
            if (local.id in remote.unreadableIds) {
                pass.problem("Could not sync “${label(local)}”: the web copy could not be read")
                continue
            }
            try {
                pushRow(local, remote.rows[local.id], pass)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "push failed for ${local.id}", e)
                pass.problem("Could not sync “${label(local)}”: ${describe(e)}", isConnectionError(e))
            }
        }

        var latest = remote
        var skip: Set<String> = emptySet()
        if (pass.wrote) {
            try {
                latest = fetchRemote(ownerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Pull from the list read before the push, but leave the pushed rows alone:
                // that list predates their new server state.
                Log.w(TAG, "re-read after push failed", e)
                pass.problem(describe(e), isConnectionError(e))
                skip = touched
            }
        }
        pull(latest, skip, ownerLookedUp, pass)
        // Only against a list read after this pass's writes: an older one can still list a
        // photo the push just removed, which would cancel that photo's delete.
        if (skip.isEmpty()) cleanUpImages(latest)
        return pass.problems
    }

    private suspend fun pushRow(local: Task, other: PortalTask?, pass: Pass) {
        val now = System.currentTimeMillis()
        val nowIso = toIso(now)
        when (val action = SyncPlan.decide(local, other)) {
            SyncPlan.Action.Delete -> {
                val server = other ?: return
                pass.wrote = true
                // From here the server row may be gone, even if the reply is lost. If the task
                // is restored (Undo), a missing server row must mean "insert it again", not
                // "deleted on the web".
                repo.markNeverSynced(local.id)
                supabase.from(TABLE).delete {
                    filter { eq("id", local.id) }
                }
                // Files go only after a grace period and a fresh check, so an Undo that puts
                // the task back keeps its photos.
                queueOwnImages(local.id, server.images.orEmpty() + local.images)
                // Restored while the delete was in flight: it stays, and the next pass inserts it.
                repo.hardDeleteIfUnchanged(local.id, local.updatedAt)
            }

            SyncPlan.Action.DropLocal -> {
                if (local.deleted) {
                    queueOwnImages(local.id, local.images)
                    repo.hardDeleteIfUnchanged(local.id, local.updatedAt)
                } else if (!pass.unsafe) {
                    // Deleted on the web, or moved to another person: the web wins.
                    repo.hardDeleteIfUnchanged(local.id, local.updatedAt)
                }
            }

            SyncPlan.Action.Insert -> {
                pass.wrote = true
                // If the reply is lost, the next pass knows the new server row as its own.
                repo.recordSent(local.id, SyncPlan.Content.of(local).encode())
                val inserted = try {
                    supabase.from(TABLE).insert(
                        PortalTaskInsert(
                            id = local.id,
                            personId = pass.ownerId,
                            title = local.title,
                            notes = local.notes.ifBlank { null },
                            status = local.toStatus(),
                            sort = local.sortOrder,
                            images = local.images,
                            area = TaskArea.normalize(local.area),
                            completedAt = SyncPlan.completedAtFor(local, null, nowIso),
                            createdAt = toIso(local.createdAt),
                            updatedAt = toIso(local.updatedAt),
                            updatedBy = pass.email
                        )
                    ) {
                        select()
                    }
                } catch (e: RestException) {
                    if (!isDuplicateKey(e)) throw e
                    // Refused, so the row on the web did not start as this send.
                    repo.recordSent(local.id, null)
                    resolveDuplicate(local, pass)
                    return
                }
                val row = decodeRows(inserted.data).firstOrNull() ?: return
                confirm(local, row, now)
            }

            SyncPlan.Action.MarkClean -> {
                val server = other ?: return
                confirm(local, server, now)
            }

            SyncPlan.Action.ApplyRemote -> {
                val server = other ?: return
                // No photo file is queued for deletion here: a merge keeps every photo the
                // phone added that the web did not remove, and the web deletes the files it
                // removes itself.
                repo.applyRemoteOver(server.toLocal(now), local.updatedAt)
            }

            SyncPlan.Action.Patch -> {
                val server = other ?: return
                push(local, SyncPlan.Content.of(local), server, pass, now)
            }

            is SyncPlan.Action.Merge -> {
                val server = other ?: return
                push(local, action.content, server, pass, now)
            }
        }
    }

    /**
     * Stores [content] with [server] as the new merge base, then PATCHes it over [server]. The
     * base moves first, so a push whose reply is lost is later judged against the version it
     * was sent on top of. The web's side of a merge is kept locally even if the PATCH does not
     * land; the row stays dirty until it does.
     */
    private suspend fun push(
        local: Task,
        content: SyncPlan.Content,
        server: PortalTask,
        pass: Pass,
        now: Long
    ) {
        val next = local.withContent(content).copy(
            remoteBase = SyncPlan.Content.of(server).encode(),
            remoteUpdatedAt = server.updatedAt
        )
        // Edited again meanwhile: the next pass decides again with that edit.
        if (!repo.applyMerge(next, local.updatedAt)) return
        patch(next, server, pass, now)
    }

    /** Conditional PATCH of [local]'s content over [server]; clears dirty once it lands. */
    private suspend fun patch(local: Task, server: PortalTask, pass: Pass, now: Long) {
        pass.wrote = true
        // If the reply is lost, the next pass knows this server state as the phone's own write.
        repo.recordSent(local.id, SyncPlan.Content.of(local).encode())
        val result = supabase.from(TABLE).update(
            PortalTaskPatch(
                title = local.title,
                notes = local.notes.ifBlank { null },
                status = local.toStatus(),
                sort = local.sortOrder,
                images = local.images,
                area = TaskArea.normalize(local.area),
                completedAt = SyncPlan.completedAtFor(local, server, toIso(now)),
                updatedAt = toIso(local.updatedAt),
                updatedBy = pass.email
            )
        ) {
            select()
            filter {
                eq("id", local.id)
                // Lands only if the row is still the version this decision was based on.
                eq("updated_at", canonicalIso(server.updatedAt))
            }
        }
        val row = decodeRows(result.data).firstOrNull()
        if (row == null) {
            // The guard matched no row, so nothing was written: this is no lost reply.
            val nothingWritten = try {
                json.parseToJsonElement(result.data).jsonArray.isEmpty()
            } catch (e: Exception) {
                false
            }
            if (nothingWritten) repo.recordSent(local.id, null)
            // Normally the web changed the row meanwhile: it stays dirty and the next pass
            // (started by that very change) decides again. If the row did not change, the
            // guard itself failed; say so instead of retrying silently.
            val current = fetchRawById(local.id)
            val stamp = (current?.get("updated_at") as? JsonPrimitive)?.contentOrNull
            if (stamp != null && SyncPlan.sameInstant(stamp, server.updatedAt)) {
                pass.problem("Could not save “${label(local)}” to the web")
            }
            return
        }
        confirm(local, row, now)
    }

    /** The server holds [row] for this task; clear dirty unless it was edited meanwhile. */
    private suspend fun confirm(local: Task, row: PortalTask, now: Long) {
        repo.recordServerState(
            id = local.id,
            syncedAt = now,
            remoteUpdatedAt = row.updatedAt,
            remoteCompletedAt = row.completedAt,
            remoteBase = SyncPlan.Content.of(row).encode()
        )
        repo.markClean(local.id, local.updatedAt, parseIso(row.updatedAt))
    }

    private suspend fun resolveDuplicate(local: Task, pass: Pass) {
        val existing = fetchRawById(local.id)
        val ownerOfRow = (existing?.get("person_id") as? JsonPrimitive)?.contentOrNull
        when {
            // Moved to another person on the web: the web wins, as for a missing synced row.
            ownerOfRow != null && ownerOfRow != pass.ownerId -> {
                if (!pass.unsafe) repo.hardDeleteIfUnchanged(local.id, local.updatedAt)
            }
            // Ours after all; the next pass sees it in the list and compares normally.
            ownerOfRow == pass.ownerId -> Unit
            else -> pass.problem("Could not sync “${label(local)}”: it already exists on the web")
        }
    }

    private suspend fun pull(remote: RemoteSnapshot, skip: Set<String>, ownerLookedUp: Boolean, pass: Pass) {
        val now = System.currentTimeMillis()
        val localAll = repo.getAll()
        val localById = localAll.associateBy { it.id }
        for (row in remote.rows.values) {
            if (row.id in skip) continue
            val local = localById[row.id]
            if (local != null && local.dirty) continue
            val incoming = row.toLocal(now)
            if (local != null && sameAsLocal(local, incoming)) continue
            repo.applyRemote(incoming)
        }
        val unreadable = remote.unreadableIds.size + remote.anonymous
        if (unreadable > 0) {
            pass.problem("$unreadable task(s) on the web could not be read")
        }

        val synced = localAll.count { !it.deleted && it.syncedAt > 0L }
        if (ownerLookedUp || remote.unsafeFor(synced)) {
            if (remote.isEmpty && synced >= MIN_ROWS_FOR_EMPTY_CHECK) {
                pass.problem("The web list came back empty, so no tasks were removed from the phone")
            }
            return
        }
        for (local in localAll) {
            if (local.id in skip || local.dirty || local.syncedAt <= 0L) continue
            if (remote.has(local.id)) continue
            // Confirmed on the server before and gone now: deleted or moved on the web.
            repo.hardDeleteIfClean(local.id)
        }
    }

    private fun sameAsLocal(local: Task, incoming: Task): Boolean {
        if (local.deleted || local.syncedAt <= 0L) return false
        return local.copy(recurring = false, syncedAt = 0L) ==
            incoming.copy(recurring = false, syncedAt = 0L)
    }

    private fun queueOwnImages(taskId: String, paths: List<String>) {
        paths.distinct()
            .filter { it.startsWith("$taskId/") }
            .forEach { images.removeLater(taskId, it) }
    }

    /**
     * Deletes queued storage files once it is safe: the task's change is on the server (row
     * clean) and no server row for that task lists the file any more.
     */
    private suspend fun cleanUpImages(remote: RemoteSnapshot) {
        val pending = images.pendingRemovals()
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        for (entry in pending) {
            val age = now - entry.queuedAt
            if (age < IMAGE_GRACE_MS) continue
            if (age > IMAGE_GIVE_UP_MS) {
                images.dropPending(entry)
                continue
            }
            try {
                val local = repo.getById(entry.taskId)
                if (local != null && local.dirty) continue
                if (local != null && entry.path in local.images) {
                    images.dropPending(entry)
                    continue
                }
                if (entry.taskId in remote.unreadableIds) continue
                val serverImages: List<String>? = if (entry.taskId in remote.rows) {
                    remote.rows.getValue(entry.taskId).images.orEmpty()
                } else {
                    // Not in the owner's list: it may live under another person now.
                    val raw = fetchRawById(entry.taskId)
                    if (raw == null) null else (stringList(raw["images"]) ?: continue)
                }
                when (SyncPlan.imageCleanup(entry.path, local != null, serverImages)) {
                    SyncPlan.ImageCleanup.Delete -> {
                        images.deleteNow(entry.path)
                        images.dropPending(entry)
                    }
                    SyncPlan.ImageCleanup.Keep -> images.dropPending(entry)
                    SyncPlan.ImageCleanup.Wait -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "image cleanup failed for ${entry.path}", e)
            }
        }
    }

    // ---- server reads ----

    private suspend fun fetchRemote(ownerId: String): RemoteSnapshot {
        val rows = LinkedHashMap<String, PortalTask>()
        val unreadable = mutableSetOf<String>()
        var anonymous = 0
        var fetched = 0L
        var total: Long? = null
        var lastId: String? = null
        var complete = false
        // Keyset pages ordered by id, so a row added or removed mid-read cannot push another
        // one out of the list (a missing row would read as "deleted on the web").
        for (attempt in 1..MAX_PAGES) {
            val after = lastId
            val result = supabase.from(TABLE).select {
                filter {
                    eq("person_id", ownerId)
                    if (after != null) gt("id", after)
                }
                order("id", Order.ASCENDING)
                limit(PAGE_SIZE)
                if (after == null) count(Count.EXACT)
            }
            if (after == null) total = result.countOrNull()
            val page = json.parseToJsonElement(result.data).jsonArray
            fetched += page.size
            var pageLastId: String? = null
            for (element in page) {
                val id = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                if (id == null) {
                    anonymous++
                    continue
                }
                pageLastId = id
                val row = decodeRow(element)
                if (row != null) rows[id] = row else unreadable += id
            }
            val known = total
            val short = page.size < PAGE_SIZE
            if (page.isEmpty() || (short && (known == null || fetched >= known))) {
                complete = true
                break
            }
            // A page with no readable id cannot be continued from; treat the list as partial.
            lastId = pageLastId ?: break
        }
        return RemoteSnapshot(rows, unreadable, anonymous, complete)
    }

    private fun decodeRow(element: JsonElement): PortalTask? {
        return try {
            val row = json.decodeFromJsonElement(PortalTask.serializer(), element)
            parseIso(row.createdAt)
            parseIso(row.updatedAt)
            row
        } catch (e: Exception) {
            Log.w(TAG, "skipping unreadable web task", e)
            null
        }
    }

    private fun decodeRows(body: String): List<PortalTask> {
        val array = try {
            json.parseToJsonElement(body).jsonArray
        } catch (e: Exception) {
            return emptyList()
        }
        return array.mapNotNull { decodeRow(it) }
    }

    /** The row with this id under any person, or null if it does not exist. */
    private suspend fun fetchRawById(id: String): JsonObject? {
        val result = supabase.from(TABLE).select {
            filter { eq("id", id) }
        }
        return json.parseToJsonElement(result.data).jsonArray.firstOrNull() as? JsonObject
    }

    private suspend fun lookUpOwner(): String {
        val people = supabase.from(PEOPLE_TABLE).select {
            order("sort", Order.ASCENDING)
        }.decodeList<PortalPerson>()
        val id = people.firstOrNull { it.name.trim().equals(OWNER_NAME, ignoreCase = true) }?.id
            ?: throw IllegalStateException("No '$OWNER_NAME' column on the web board")
        prefs.edit().putString(KEY_OWNER, id).apply()
        return id
    }

    private suspend fun personExists(id: String): Boolean {
        return supabase.from(PEOPLE_TABLE).select {
            filter { eq("id", id) }
        }.decodeList<PortalPerson>().isNotEmpty()
    }

    // ---- helpers ----

    private fun isDuplicateKey(e: RestException): Boolean {
        return (e is PostgrestRestException && e.code == "23505") || e.statusCode == 409
    }

    /** A JSON list of strings; null when it is present but not readable as one. */
    private fun stringList(element: JsonElement?): List<String>? {
        if (element == null || element is JsonNull) return emptyList()
        if (element !is JsonArray) return null
        val values = element.map { (it as? JsonPrimitive)?.contentOrNull }
        return if (values.any { it == null }) null else values.filterNotNull()
    }

    private fun label(task: Task): String {
        val title = task.title.trim()
        return if (title.length <= 40) title else title.take(39) + "…"
    }

    private fun summarize(problems: List<Problem>): String? {
        if (problems.isEmpty()) return null
        val first = problems.first().text
        return if (problems.size == 1) first else "$first (and ${problems.size - 1} more)"
    }

    private fun isConnectionError(e: Throwable): Boolean = e is IOException

    private fun describe(e: Throwable): String = when (e) {
        is RestException -> when {
            e.statusCode == 401 || (e is PostgrestRestException && e.code?.let { it in AUTH_CODES } == true) ->
                "The web did not accept the sign-in; sign in again if this stays"
            else -> "The web refused it: ${e.error.take(100)}"
        }
        is IOException -> "No connection to the web"
        is SerializationException -> "Could not read the web's reply"
        else -> e.message?.take(120) ?: e.javaClass.simpleName
    }

    companion object {
        private const val TAG = "LockList"
        private const val PREFS = "locklist_sync"
        private const val KEY_LAST_OK = "lastOkAt"
        private const val KEY_OLD_LAST_SYNC = "lastSync"
        private const val KEY_OWNER = "ownerPersonId"
        private const val TABLE = "portal_tasks"
        private const val PEOPLE_TABLE = "portal_task_people"
        private const val OWNER_NAME = "Brian"
        private const val CHANNEL_PREFIX = "locklist-portal-tasks-"
        private const val SUBSCRIBE_TIMEOUT_MS = 20_000L
        private const val PUSH_DEBOUNCE_MS = 400L
        private const val PAGE_SIZE = 500L
        private const val MAX_PAGES = 200
        private const val MIN_ROWS_FOR_EMPTY_CHECK = 3
        private const val IMAGE_GRACE_MS = 2 * 60_000L
        private const val IMAGE_GIVE_UP_MS = 30L * 24 * 60 * 60_000L
        private val AUTH_CODES = setOf("PGRST301", "PGRST302", "42501")
    }
}
