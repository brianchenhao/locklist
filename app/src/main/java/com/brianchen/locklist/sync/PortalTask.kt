package com.brianchen.locklist.sync

import com.brianchen.locklist.data.Task
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PortalPerson(
    val id: String,
    val name: String
)

@Serializable
data class PortalTask(
    val id: String,
    @SerialName("person_id") val personId: String,
    val title: String,
    val status: String,
    val sort: Int,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
) {
    fun toLocal(): Task {
        return Task(
            id = id,
            title = title,
            done = status == "done",
            sortOrder = sort,
            createdAt = parseIso(createdAt),
            updatedAt = parseIso(updatedAt),
            deleted = false
        )
    }
}

@Serializable
data class PortalTaskInsert(
    val id: String,
    @SerialName("person_id") val personId: String,
    val title: String,
    val status: String,
    val sort: Int,
    val images: List<String> = emptyList(),
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("updated_by") val updatedBy: String? = null
)

@Serializable
data class PortalTaskPatch(
    val title: String,
    val status: String,
    val sort: Int,
    @SerialName("completed_at") val completedAt: String?,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("updated_by") val updatedBy: String? = null
)

fun Task.toStatus(): String = if (done) "done" else "more"

fun Task.completedAtIso(): String? = if (done) toIso(updatedAt) else null

fun parseIso(value: String): Long {
    return OffsetDateTime.parse(value).toInstant().toEpochMilli()
}

fun toIso(millis: Long): String {
    return Instant.ofEpochMilli(millis).toString()
}
