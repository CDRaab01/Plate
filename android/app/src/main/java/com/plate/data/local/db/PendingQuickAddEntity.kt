package com.plate.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A quick-add logged while offline, queued to POST when connectivity returns. Quick-adds carry all
 * their macros client-side (no server-side food scaling), so — like Spotter's offline set logs —
 * they can be created entirely offline and synced later. Surfaced in the diary immediately via a
 * synthetic log row keyed by [localId].
 */
@Entity(tableName = "pending_quick_add")
data class PendingQuickAddEntity(
    @PrimaryKey val localId: String,
    val date: String,
    val meal: String,
    val name: String?,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val createdAt: Long,
)
