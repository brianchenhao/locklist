package com.brianchen.locklist.sync

import android.content.Context
import com.brianchen.locklist.data.TaskRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TaskSync(
    context: Context,
    private val repo: TaskRepository,
    private val supabase: SupabaseClient
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun isSignedIn(): Boolean {
        supabase.auth.awaitInitialization()
        return supabase.auth.currentSessionOrNull() != null
    }

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(io.github.jan.supabase.auth.providers.builtin.Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }

    suspend fun currentEmail(): String? {
        supabase.auth.awaitInitialization()
        return supabase.auth.currentUserOrNull()?.email
    }

    suspend fun syncOnce() = mutex.withLock {
        if (!isSignedIn()) return@withLock
        val startedAt = System.currentTimeMillis()
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
        val personId = brianPersonId()
        val email = supabase.auth.currentUserOrNull()?.email

        val remote = supabase.from("portal_tasks").select {
            filter { eq("person_id", personId) }
        }.decodeList<PortalTask>()
        val remoteById = remote.associateBy { it.id }

        val dirty = repo.getChangedSince(lastSync)
        for (local in dirty) {
            val other = remoteById[local.id]
            if (local.deleted) {
                if (other != null) {
                    supabase.from("portal_tasks").delete {
                        filter { eq("id", local.id) }
                    }
                }
                continue
            }
            if (other != null && parseIso(other.updatedAt) > local.updatedAt) continue
            if (other == null) {
                supabase.from("portal_tasks").insert(
                    PortalTaskInsert(
                        id = local.id,
                        personId = personId,
                        title = local.title,
                        status = local.toStatus(),
                        sort = local.sortOrder,
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
                        status = local.toStatus(),
                        sort = local.sortOrder,
                        completedAt = local.completedAtIso(),
                        updatedAt = toIso(local.updatedAt),
                        updatedBy = email
                    )
                ) {
                    filter { eq("id", local.id) }
                }
            }
        }

        val latestRemote = supabase.from("portal_tasks").select {
            filter { eq("person_id", personId) }
        }.decodeList<PortalTask>()
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
    }

    private suspend fun brianPersonId(): String {
        val people = supabase.from("portal_task_people").select()
            .decodeList<PortalPerson>()
        return people.firstOrNull { it.name.equals("Brian", ignoreCase = true) }?.id
            ?: error("Brian column missing on the portal task board")
    }

    companion object {
        private const val PREFS = "locklist_sync"
        private const val KEY_LAST_SYNC = "lastSync"
    }
}
