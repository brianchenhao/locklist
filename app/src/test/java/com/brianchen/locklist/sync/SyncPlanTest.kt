package com.brianchen.locklist.sync

import com.brianchen.locklist.data.Task
import com.brianchen.locklist.data.TaskStatus
import com.brianchen.locklist.sync.SyncPlan.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {
    private val t0 = "2026-09-25T10:00:00.123456+00:00"
    private val t0Millis = parseIso(t0)
    private val nowIso = "2026-09-27T08:00:00Z"

    private fun local(
        title: String = "Buy milk",
        status: String = TaskStatus.MORE,
        sort: Int = 1,
        notes: String = "",
        images: List<String> = emptyList(),
        area: String = "personal",
        updatedAt: Long = t0Millis,
        deleted: Boolean = false,
        syncedAt: Long = 1L,
        remoteUpdatedAt: String? = t0,
        remoteCompletedAt: String? = null,
        remoteBase: String? = null,
        pendingSent: String? = null
    ) = Task(
        id = "a",
        title = title,
        done = status == TaskStatus.DONE,
        sortOrder = sort,
        createdAt = t0Millis,
        updatedAt = updatedAt,
        deleted = deleted,
        status = status,
        notes = notes,
        imagePaths = TaskStatus.imagePaths(images),
        area = area,
        dirty = true,
        syncedAt = syncedAt,
        remoteCompletedAt = remoteCompletedAt,
        remoteUpdatedAt = remoteUpdatedAt,
        remoteBase = remoteBase,
        pendingSent = pendingSent
    )

    private fun remote(
        title: String = "Buy milk",
        status: String = TaskStatus.MORE,
        sort: Int = 1,
        notes: String? = null,
        images: List<String>? = emptyList(),
        area: String? = "personal",
        updatedAt: String = t0,
        completedAt: String? = null
    ) = PortalTask(
        id = "a",
        personId = "brian",
        title = title,
        notes = notes,
        status = status,
        sort = sort,
        images = images,
        area = area,
        completedAt = completedAt,
        createdAt = t0,
        updatedAt = updatedAt
    )

    /** Encoded synced fields, defaulting to what [remote] returns by default. */
    private fun content(
        title: String = "Buy milk",
        notes: String = "",
        status: String = TaskStatus.MORE,
        sort: Int = 1,
        images: List<String> = emptyList(),
        area: String = "personal"
    ) = SyncPlan.Content(title, notes, status, sort, images, area).encode()

    private val later = "2026-09-25T11:00:00Z"

    @Test
    fun equalContentMarksCleanWithoutPatch() {
        assertEquals(Action.MarkClean, SyncPlan.decide(local(), remote()))
    }

    @Test
    fun equalContentTreatsNullAndBlankNotesAsSame() {
        assertEquals(Action.MarkClean, SyncPlan.decide(local(notes = "  "), remote(notes = null)))
        assertEquals(Action.MarkClean, SyncPlan.decide(local(notes = ""), remote(notes = "")))
    }

    @Test
    fun equalContentEvenWhenServerTimestampMovedAfterOwnPush() {
        // The re-push loop case: local already matches, server updated_at is later.
        val later = "2026-09-25T11:00:00Z"
        assertEquals(
            Action.MarkClean,
            SyncPlan.decide(local(remoteUpdatedAt = null), remote(updatedAt = later))
        )
    }

    @Test
    fun webNewerAppliesRemote() {
        val later = "2026-09-25T11:00:00Z"
        val decision = SyncPlan.decide(
            local(title = "Buy oat milk", updatedAt = t0Millis + 1_000),
            remote(title = "Buy soy milk", updatedAt = later)
        )
        assertEquals(Action.ApplyRemote, decision)
    }

    @Test
    fun localNewerPatches() {
        val decision = SyncPlan.decide(
            local(title = "Buy oat milk", updatedAt = t0Millis + 60_000, remoteUpdatedAt = null),
            remote(updatedAt = t0)
        )
        assertEquals(Action.Patch, decision)
    }

    @Test
    fun unchangedServerRowPatchesEvenIfItsClockIsAhead() {
        // Server stamped our last push later than the phone's next edit (clock skew or an
        // edit during the push). The server row is exactly the version we saw, so we win.
        val serverStamp = "2026-09-25T10:05:00Z"
        val decision = SyncPlan.decide(
            local(title = "Edited", updatedAt = t0Millis + 1_000, remoteUpdatedAt = serverStamp),
            remote(updatedAt = "2026-09-25T10:05:00.000000+00:00")
        )
        assertEquals(Action.Patch, decision)
    }

    @Test
    fun changedServerRowWithNewerTimeWins() {
        val decision = SyncPlan.decide(
            local(title = "Edited", updatedAt = t0Millis + 1_000, remoteUpdatedAt = t0),
            remote(title = "Web edit", updatedAt = "2026-09-25T10:05:00Z")
        )
        assertEquals(Action.ApplyRemote, decision)
    }

    @Test
    fun differentSortImagesAreaOrStatusIsNotEqual() {
        assertEquals(Action.Patch, SyncPlan.decide(local(sort = 2, updatedAt = t0Millis + 5), remote()))
        assertEquals(
            Action.Patch,
            SyncPlan.decide(local(images = listOf("a/1.jpg"), updatedAt = t0Millis + 5), remote())
        )
        assertEquals(Action.Patch, SyncPlan.decide(local(area = "work", updatedAt = t0Millis + 5), remote()))
        assertEquals(
            Action.Patch,
            SyncPlan.decide(local(status = TaskStatus.DONE, updatedAt = t0Millis + 5), remote())
        )
    }

    @Test
    fun deletedLocallyAndOnServerDeletes() {
        assertEquals(Action.Delete, SyncPlan.decide(local(deleted = true), remote()))
    }

    @Test
    fun deletedLocallyAndGoneFromServerDropsLocal() {
        assertEquals(Action.DropLocal, SyncPlan.decide(local(deleted = true), null))
        assertEquals(Action.DropLocal, SyncPlan.decide(local(deleted = true, syncedAt = 0L), null))
    }

    @Test
    fun missingAfterBeingSyncedDropsLocal() {
        // Deleted or moved to another person on the web: never re-insert it.
        assertEquals(Action.DropLocal, SyncPlan.decide(local(syncedAt = 5L), null))
    }

    @Test
    fun missingAndNeverSyncedInserts() {
        assertEquals(Action.Insert, SyncPlan.decide(local(syncedAt = 0L, remoteUpdatedAt = null), null))
    }

    @Test
    fun completedAtIsNullWhenNotDone() {
        assertNull(SyncPlan.completedAtFor(local(), remote(), nowIso))
        assertNull(
            SyncPlan.completedAtFor(
                local(),
                remote(status = TaskStatus.DONE, completedAt = t0),
                nowIso
            )
        )
    }

    @Test
    fun completedAtKeepsServerStampWhileDone() {
        val stamp = "2026-09-20T09:00:00Z"
        assertEquals(
            stamp,
            SyncPlan.completedAtFor(
                local(status = TaskStatus.DONE, title = "Renamed"),
                remote(status = TaskStatus.DONE, completedAt = stamp),
                nowIso
            )
        )
    }

    @Test
    fun completedAtStampsNowWhenBecomingDone() {
        assertEquals(
            nowIso,
            SyncPlan.completedAtFor(local(status = TaskStatus.DONE), remote(), nowIso)
        )
    }

    @Test
    fun completedAtReusesTheStampAnUndoPutBack() {
        // Unticked by mistake (pushed, server now not done), then Undo restored the old stamp.
        val stamp = "2026-09-20T09:00:00Z"
        assertEquals(
            stamp,
            SyncPlan.completedAtFor(local(status = TaskStatus.DONE, remoteCompletedAt = stamp), remote(), nowIso)
        )
    }

    @Test
    fun completedAtOnInsertPrefersRememberedStamp() {
        val stamp = "2026-09-20T09:00:00Z"
        assertEquals(
            stamp,
            SyncPlan.completedAtFor(local(status = TaskStatus.DONE, remoteCompletedAt = stamp), null, nowIso)
        )
        assertEquals(nowIso, SyncPlan.completedAtFor(local(status = TaskStatus.DONE), null, nowIso))
        assertNull(SyncPlan.completedAtFor(local(), null, nowIso))
    }

    @Test
    fun sameInstantIgnoresFormatting() {
        assertTrue(SyncPlan.sameInstant("2026-09-25T10:00:00.123456+00:00", "2026-09-25T10:00:00.123456Z"))
        assertTrue(SyncPlan.sameInstant("2026-09-25T12:00:00+02:00", "2026-09-25T10:00:00Z"))
        assertFalse(SyncPlan.sameInstant("2026-09-25T10:00:00.123456Z", "2026-09-25T10:00:00.123457Z"))
        assertFalse(SyncPlan.sameInstant(null, "2026-09-25T10:00:00Z"))
    }

    @Test
    fun canonicalIsoUsesUtcWithoutPlusSign() {
        assertEquals("2026-09-25T10:00:00.123456Z", canonicalIso("2026-09-25T10:00:00.123456+00:00"))
        assertFalse(canonicalIso("2026-09-25T12:00:00+02:00").contains('+'))
    }

    // ---- per-field merge when the web also changed the row ----

    private fun merged(decision: Action): SyncPlan.Content {
        assertTrue("expected a merge, got $decision", decision is Action.Merge)
        return (decision as Action.Merge).content
    }

    @Test
    fun webReorderKeepsNotesTypedOffline() {
        // Phone typed notes offline; later a web reorder of a neighbour changed only this
        // row's sort. The notes must survive and the new sort must be kept.
        val decision = SyncPlan.decide(
            local(
                notes = "call before noon",
                sort = 5,
                updatedAt = t0Millis + 1_000,
                remoteBase = content(sort = 5)
            ),
            remote(sort = 6, updatedAt = later)
        )
        val result = merged(decision)
        assertEquals("call before noon", result.notes)
        assertEquals(6, result.sort)
    }

    @Test
    fun webReorderKeepsOfflineTickAndItsDoneSort() {
        val decision = SyncPlan.decide(
            local(
                status = TaskStatus.DONE,
                sort = 9,
                updatedAt = t0Millis + 1_000,
                remoteBase = content(sort = 3)
            ),
            remote(sort = 4, updatedAt = later)
        )
        // The web's sort belonged to the old column and the phone's to Done, so the phone's
        // row goes up unchanged: the tick is not lost to the reorder.
        assertEquals(Action.Patch, decision)
        assertEquals(
            SyncPlan.Content("Buy milk", "", TaskStatus.DONE, 9, emptyList(), "personal"),
            SyncPlan.merge(
                base = SyncPlan.Content("Buy milk", "", TaskStatus.MORE, 3, emptyList(), "personal"),
                mine = SyncPlan.Content("Buy milk", "", TaskStatus.DONE, 9, emptyList(), "personal"),
                server = SyncPlan.Content("Buy milk", "", TaskStatus.MORE, 4, emptyList(), "personal"),
                webNewer = true
            )
        )
    }

    @Test
    fun webReorderPlusRenameKeepsOfflineTick() {
        val decision = SyncPlan.decide(
            local(
                status = TaskStatus.DONE,
                sort = 9,
                updatedAt = t0Millis + 1_000,
                remoteBase = content(sort = 3)
            ),
            remote(title = "Buy milk today", sort = 4, updatedAt = later)
        )
        val result = merged(decision)
        assertEquals(TaskStatus.DONE, result.status)
        assertEquals(9, result.sort)
        assertEquals("Buy milk today", result.title)
    }

    @Test
    fun photoAttachedOnPhoneSurvivesWebEdit() {
        val decision = SyncPlan.decide(
            local(images = listOf("a/1.jpg"), updatedAt = t0Millis + 1_000, remoteBase = content()),
            remote(title = "Buy milk today", updatedAt = later)
        )
        val result = merged(decision)
        assertEquals(listOf("a/1.jpg"), result.images)
        assertEquals("Buy milk today", result.title)
    }

    @Test
    fun photoAddsAndRemovesFromBothSidesAreKept() {
        val decision = SyncPlan.decide(
            local(
                images = listOf("a/1.jpg", "a/3.jpg"),
                updatedAt = t0Millis + 1_000,
                remoteBase = content(images = listOf("a/1.jpg", "a/2.jpg"))
            ),
            remote(images = listOf("a/1.jpg", "a/2.jpg", "a/4.jpg"), updatedAt = later)
        )
        // Phone removed 2 and added 3; the web added 4.
        assertEquals(listOf("a/1.jpg", "a/4.jpg", "a/3.jpg"), merged(decision).images)
    }

    @Test
    fun photoRemovedOnTheWebStaysRemoved() {
        // Not a set union: the web deleted photo 2 and its file, so it must not come back.
        val decision = SyncPlan.decide(
            local(
                title = "Renamed",
                images = listOf("a/1.jpg", "a/2.jpg"),
                updatedAt = t0Millis + 1_000,
                remoteBase = content(images = listOf("a/1.jpg", "a/2.jpg"))
            ),
            remote(images = listOf("a/1.jpg"), updatedAt = later)
        )
        val result = merged(decision)
        assertEquals(listOf("a/1.jpg"), result.images)
        assertEquals("Renamed", result.title)
    }

    @Test
    fun photosRemovedOnEachSideAreAllRemoved() {
        val decision = SyncPlan.decide(
            local(
                images = listOf("a/1.jpg"),
                updatedAt = t0Millis + 1_000,
                remoteBase = content(images = listOf("a/1.jpg", "a/2.jpg"))
            ),
            remote(images = listOf("a/2.jpg"), updatedAt = later)
        )
        assertEquals(emptyList<String>(), merged(decision).images)
    }

    @Test
    fun sameFieldChangedOnBothSidesGoesToTheNewerSide() {
        // Ticked on the phone, then moved to Review on the web later: the web wins.
        val webLater = SyncPlan.decide(
            local(status = TaskStatus.DONE, sort = 9, updatedAt = t0Millis + 1_000, remoteBase = content()),
            remote(status = TaskStatus.REVIEW, sort = 2, updatedAt = later)
        )
        assertEquals(Action.ApplyRemote, webLater)
        // The same edits with the phone's made last: the phone wins.
        val phoneLater = SyncPlan.decide(
            local(
                status = TaskStatus.DONE,
                sort = 9,
                updatedAt = parseIso(later) + 60_000,
                remoteBase = content()
            ),
            remote(status = TaskStatus.REVIEW, sort = 2, updatedAt = later)
        )
        assertEquals(Action.Patch, phoneLater)
    }

    @Test
    fun webChangeToFieldsThePhoneLeftAloneIsApplied() {
        // The phone's edit already matches the web on its field; the web also renamed it.
        val decision = SyncPlan.decide(
            local(notes = "x", updatedAt = t0Millis + 1_000, remoteBase = content()),
            remote(title = "Renamed", notes = "x", updatedAt = later)
        )
        assertEquals(Action.ApplyRemote, decision)
    }

    @Test
    fun unchangedWebRowWithBaseStillPatches() {
        val decision = SyncPlan.decide(
            local(title = "Edited", updatedAt = t0Millis + 1_000, remoteUpdatedAt = null, remoteBase = content()),
            remote(updatedAt = later)
        )
        assertEquals(Action.Patch, decision)
    }

    // ---- a push whose reply was lost ----

    @Test
    fun serverHoldingWhatThePhoneLastSentIsItsOwnWrite() {
        // Tick sent, server stored it (updated_at moved), reply lost, then Undo on the phone.
        val decision = SyncPlan.decide(
            local(
                status = TaskStatus.MORE,
                sort = 3,
                updatedAt = t0Millis + 1_000,
                remoteBase = content(sort = 3),
                pendingSent = content(status = TaskStatus.DONE, sort = 8)
            ),
            remote(status = TaskStatus.DONE, sort = 8, updatedAt = later)
        )
        assertEquals(Action.Patch, decision)
    }

    @Test
    fun ownLostWriteThenWebEditKeepsTheUndo() {
        val decision = SyncPlan.decide(
            local(
                status = TaskStatus.MORE,
                sort = 3,
                updatedAt = t0Millis + 1_000,
                remoteBase = content(sort = 3),
                pendingSent = content(status = TaskStatus.DONE, sort = 8)
            ),
            remote(status = TaskStatus.DONE, sort = 8, notes = "added on web", updatedAt = later)
        )
        val result = merged(decision)
        assertEquals(TaskStatus.MORE, result.status)
        assertEquals(3, result.sort)
        assertEquals("added on web", result.notes)
    }

    @Test
    fun webEditsAfterALandedLostPushAreMeasuredFromThatPush() {
        // Renamed and ticked; the PATCH landed but its reply was lost. The server still shows
        // the rename, so the tick landed too, and the web's later untick wins.
        val sent = content(title = "T1", status = TaskStatus.DONE, sort = 9)
        val decision = SyncPlan.decide(
            local(
                title = "T1",
                status = TaskStatus.DONE,
                sort = 9,
                updatedAt = t0Millis + 1_000,
                remoteBase = content(sort = 3),
                pendingSent = sent
            ),
            remote(title = "T1", status = TaskStatus.MORE, sort = 12, updatedAt = later)
        )
        assertEquals(Action.ApplyRemote, decision)
    }

    @Test
    fun webRevertOfOneFieldOfALandedLostPushWins() {
        // Title and notes landed (reply lost); the web then put the title back.
        val decision = SyncPlan.decide(
            local(
                title = "T1",
                notes = "n",
                updatedAt = t0Millis + 1_000,
                remoteBase = content(),
                pendingSent = content(title = "T1", notes = "n")
            ),
            remote(title = "Buy milk", notes = "n", updatedAt = later)
        )
        assertEquals(Action.ApplyRemote, decision)
    }

    @Test
    fun photoTheWebRemovedAfterALandedLostPushStaysRemoved() {
        // Rename and photo 3 landed (reply lost); the web then removed photo 3 and its file.
        val decision = SyncPlan.decide(
            local(
                title = "T1",
                images = listOf("a/1.jpg", "a/3.jpg"),
                updatedAt = t0Millis + 1_000,
                remoteBase = content(images = listOf("a/1.jpg")),
                pendingSent = content(title = "T1", images = listOf("a/1.jpg", "a/3.jpg"))
            ),
            remote(title = "T1", images = listOf("a/1.jpg"), updatedAt = later)
        )
        assertEquals(Action.ApplyRemote, decision)
    }

    @Test
    fun aPhotoOnlyThePhoneCouldHaveAddedProvesTheLostPushLanded() {
        // Rename and photo 3 landed (reply lost); the web renamed it back and added photo 4.
        val decision = SyncPlan.decide(
            local(
                title = "T1",
                images = listOf("a/3.jpg"),
                updatedAt = t0Millis + 1_000,
                remoteBase = content(),
                pendingSent = content(title = "T1", images = listOf("a/3.jpg"))
            ),
            remote(images = listOf("a/3.jpg", "a/4.jpg"), updatedAt = later)
        )
        assertEquals(Action.ApplyRemote, decision)
    }

    @Test
    fun lostPushTheServerDoesNotShowKeepsThePhoneEdit() {
        // A tick whose reply was lost, then a web reorder moved this row's sort. Nothing shows
        // that the tick arrived (the web may have reordered the old column), so it is kept.
        val decision = SyncPlan.decide(
            local(
                status = TaskStatus.DONE,
                sort = 9,
                updatedAt = t0Millis + 1_000,
                remoteBase = content(sort = 3),
                pendingSent = content(status = TaskStatus.DONE, sort = 9)
            ),
            remote(sort = 4, updatedAt = later)
        )
        assertEquals(Action.Patch, decision)
    }

    @Test
    fun lostInsertReplyStillMergesFieldByField() {
        // The INSERT landed but its reply was lost, so the row has no base yet. The server
        // row began as that insert: the phone's notes and the web's title both survive.
        val decision = SyncPlan.decide(
            local(
                notes = "phone note",
                updatedAt = t0Millis + 1_000,
                syncedAt = 0L,
                remoteUpdatedAt = null,
                pendingSent = content()
            ),
            remote(title = "Web title", updatedAt = later)
        )
        val result = merged(decision)
        assertEquals("phone note", result.notes)
        assertEquals("Web title", result.title)
    }

    @Test
    fun restoredDeleteWhoseServerRowIsGoneIsInsertedAgain() {
        // A DELETE that went out clears syncedAt first: after an Undo, a missing server row
        // means "insert", not "deleted on the web".
        assertEquals(Action.Insert, SyncPlan.decide(local(syncedAt = 0L, remoteUpdatedAt = null), null))
    }

    @Test
    fun contentRoundTripsAndBadValuesDecodeToNull() {
        val encoded = content(title = "Say \"hi\"\nthen go", images = listOf("a/1.jpg"))
        assertEquals(encoded, SyncPlan.Content.decode(encoded)?.encode())
        assertNull(SyncPlan.Content.decode(null))
        assertNull(SyncPlan.Content.decode("not json"))
    }

    @Test
    fun rowWithUnreadableBaseFallsBackToNewerWins() {
        val decision = SyncPlan.decide(
            local(title = "Edited", updatedAt = t0Millis + 1_000, remoteBase = "garbage"),
            remote(title = "Web edit", updatedAt = later)
        )
        assertEquals(Action.ApplyRemote, decision)
    }

    // ---- queued photo deletes ----

    @Test
    fun photoCleanupWaitsWhenTheServerListIsBehindThePhone() {
        assertEquals(
            SyncPlan.ImageCleanup.Wait,
            SyncPlan.imageCleanup("a/1.jpg", localExists = true, serverImages = listOf("a/1.jpg"))
        )
    }

    @Test
    fun photoCleanupDeletesWhenNoServerRowUsesIt() {
        assertEquals(
            SyncPlan.ImageCleanup.Delete,
            SyncPlan.imageCleanup("a/1.jpg", localExists = true, serverImages = listOf("a/2.jpg"))
        )
        assertEquals(
            SyncPlan.ImageCleanup.Delete,
            SyncPlan.imageCleanup("a/1.jpg", localExists = false, serverImages = null)
        )
    }

    @Test
    fun photoCleanupKeepsAFileStillUsedByAMovedTask() {
        assertEquals(
            SyncPlan.ImageCleanup.Keep,
            SyncPlan.imageCleanup("a/1.jpg", localExists = false, serverImages = listOf("a/1.jpg"))
        )
    }
}
