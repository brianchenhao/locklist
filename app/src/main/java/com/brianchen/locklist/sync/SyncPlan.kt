package com.brianchen.locklist.sync

import com.brianchen.locklist.data.Task
import com.brianchen.locklist.data.TaskArea
import com.brianchen.locklist.data.TaskStatus
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pure push decisions for one dirty local row, kept free of Android and network code so the
 * rules can be unit tested. [other] is the server row with the same id among the owner's rows.
 */
object SyncPlan {
    private val json = Json { ignoreUnknownKeys = true }

    /** The synced fields of a row, normalized the same way on both sides. */
    @Serializable
    data class Content(
        val title: String,
        val notes: String,
        val status: String,
        val sort: Int,
        val images: List<String>,
        val area: String
    ) {
        fun encode(): String = json.encodeToString(serializer(), this)

        companion object {
            fun of(task: Task) = Content(
                title = task.title,
                notes = task.notes.ifBlank { "" },
                status = TaskStatus.normalize(task.status),
                sort = task.sortOrder,
                images = task.images,
                area = TaskArea.normalize(task.area)
            )

            fun of(row: PortalTask) = Content(
                title = row.title,
                notes = row.notes.orEmpty().ifBlank { "" },
                status = TaskStatus.normalize(row.status),
                sort = row.sort,
                images = row.images.orEmpty(),
                area = TaskArea.normalize(row.area)
            )

            /** Null for a missing or unreadable value. */
            fun decode(value: String?): Content? {
                if (value.isNullOrBlank()) return null
                return try {
                    json.decodeFromString(serializer(), value)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    sealed interface Action {
        /** Deleted on the phone and still on the server: DELETE it there. */
        data object Delete : Action

        /** Gone from the owner's list: never pushed and deleted, or deleted/moved on the web. */
        data object DropLocal : Action

        /** Never reached the server: INSERT it. */
        data object Insert : Action

        /** Server already holds the same content: no request, just record the server state. */
        data object MarkClean : Action

        /** The web changed the row and nothing the phone changed survives the merge. */
        data object ApplyRemote : Action

        /** Only the phone changed the row (or it wins every conflict): conditional PATCH. */
        data object Patch : Action

        /** Both sides changed it: store [content] locally, then PATCH it. */
        data class Merge(val content: Content) : Action
    }

    fun decide(local: Task, other: PortalTask?): Action {
        if (local.deleted) return if (other != null) Action.Delete else Action.DropLocal
        if (other == null) return if (local.syncedAt > 0L) Action.DropLocal else Action.Insert
        val mine = Content.of(local)
        val server = Content.of(other)
        if (mine == server) return Action.MarkClean
        // If the server row is exactly the version the phone last saw, or exactly what it last
        // sent in a push whose reply was lost, nobody else touched it and the phone's edit wins
        // whatever the two clocks say.
        val sent = Content.decode(local.pendingSent)
        val webChanged = server != sent && (
            local.remoteUpdatedAt == null || !sameInstant(local.remoteUpdatedAt, other.updatedAt)
            )
        if (!webChanged) return Action.Patch
        val webNewer = parseIso(other.updatedAt) > local.updatedAt
        // Rows last seen before v0.6.0 have no base: the newer side wins as a whole. A row whose
        // insert reply was lost has none either, but its server row began as that insert.
        val seen = Content.decode(local.remoteBase)
            ?: sent?.takeIf { local.syncedAt == 0L }
            ?: return if (webNewer) Action.ApplyRemote else Action.Patch
        // A push whose reply was lost was sent on top of [seen]. Once the server shows it
        // landed, the web's later edits are measured from it; while that is unknown, the
        // phone's unconfirmed edit is kept (it may never have arrived).
        val base = if (sent != null && landed(seen, sent, server)) sent else seen
        return when (val merged = merge(base, mine, server, webNewer)) {
            server -> Action.ApplyRemote
            mine -> Action.Patch
            else -> Action.Merge(merged)
        }
    }

    /**
     * True when [server] holds a value that only the push of [sent] (made on top of [base])
     * could have written. Sort does not count: the web renumbers a whole column on a reorder,
     * so an equal sort proves nothing.
     */
    private fun landed(base: Content, sent: Content, server: Content): Boolean {
        fun <T> shows(b: T, s: T, now: T) = s != b && now == s
        return shows(base.title, sent.title, server.title) ||
            shows(base.notes, sent.notes, server.notes) ||
            shows(base.status, sent.status, server.status) ||
            shows(base.area, sent.area, server.area) ||
            shows(base.images, sent.images, server.images) ||
            sent.images.any { it !in base.images && it in server.images }
    }

    /**
     * Per-field three-way merge against [base], the server content the phone's edits were
     * made on top of. A field the web did not change keeps the phone's value (so a web reorder
     * never undoes a phone edit); a field only the web changed takes the web's; a field both
     * changed goes to the newer side. Photos merge the same way one by one: a photo added or
     * removed on either side stays added or removed.
     */
    fun merge(base: Content, mine: Content, server: Content, webNewer: Boolean): Content {
        fun <T> pick(b: T, l: T, s: T): T = when {
            s == b -> l
            l == b || l == s -> s
            webNewer -> s
            else -> l
        }
        val status = pick(base.status, mine.status, server.status)
        // A sort only means something inside its column: it follows the side whose column won.
        val sort = when {
            mine.status == server.status -> pick(base.sort, mine.sort, server.sort)
            status == mine.status -> mine.sort
            else -> server.sort
        }
        return Content(
            title = pick(base.title, mine.title, server.title),
            notes = pick(base.notes, mine.notes, server.notes),
            status = status,
            sort = sort,
            images = mergeImages(base.images, mine.images, server.images),
            area = pick(base.area, mine.area, server.area)
        )
    }

    /** The server's list with the phone's removals taken out and its additions appended. */
    private fun mergeImages(base: List<String>, mine: List<String>, server: List<String>): List<String> {
        if (server == base) return mine
        val removed = base.toSet() - mine.toSet()
        val added = mine.filter { it !in base && it !in server }
        return (server.filter { it !in removed } + added).distinct()
    }

    /** [content] on top of [task]; everything else (ids, flags, sync state) is kept. */
    fun Task.withContent(content: Content): Task = copy(
        title = content.title,
        notes = content.notes,
        status = content.status,
        done = content.status == TaskStatus.DONE,
        sortOrder = content.sort,
        imagePaths = TaskStatus.imagePaths(content.images),
        area = content.area
    )

    fun sameContent(local: Task, other: PortalTask): Boolean = Content.of(local) == Content.of(other)

    /**
     * completed_at to send. Keeps the server's stamp while the task stays done and clears it
     * when it is not done. When the task becomes done (or is inserted again), it reuses the
     * stamp the phone still holds, which an Undo puts back, and stamps now only without one.
     * [other] null means INSERT.
     */
    fun completedAtFor(local: Task, other: PortalTask?, nowIso: String): String? {
        if (TaskStatus.normalize(local.status) != TaskStatus.DONE) return null
        if (other != null && TaskStatus.normalize(other.status) == TaskStatus.DONE) return other.completedAt
        return local.remoteCompletedAt ?: nowIso
    }

    enum class ImageCleanup {
        /** Delete the file and forget the entry. */
        Delete,

        /** The file is still in use: forget the entry, keep the file. */
        Keep,

        /** Not sure yet: keep the entry for a later pass. */
        Wait
    }

    /**
     * A queued photo delete whose task is clean on the phone and no longer lists [path].
     * [serverImages] is the server row's list, null when no server row exists at all.
     */
    fun imageCleanup(path: String, localExists: Boolean, serverImages: List<String>?): ImageCleanup =
        when {
            serverImages == null || path !in serverImages -> ImageCleanup.Delete
            // The phone's row no longer lists it but the server list does: that list predates
            // the push that removed it. Look again next pass rather than lose the delete.
            localExists -> ImageCleanup.Wait
            // Only a row that now lives under another person uses it.
            else -> ImageCleanup.Keep
        }

    fun sameInstant(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        if (a == b) return true
        return try {
            OffsetDateTime.parse(a).toInstant() == OffsetDateTime.parse(b).toInstant()
        } catch (_: Exception) {
            false
        }
    }
}
