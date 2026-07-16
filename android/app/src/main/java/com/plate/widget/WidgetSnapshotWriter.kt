package com.plate.widget

import com.plate.data.local.SnapshotStore
import com.plate.data.remote.DailyLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Persists today's remaining-macros view for the Glance widget and redraws it. Home and the diary
 * both call this after the day's numbers change, so the widget stays in step with the app. Only
 * today's log is a valid widget source — callers pass today's [DailyLog]. Mirrors how Magpie writes
 * its Home snapshot in the ViewModel, with Cookbook's `WidgetRefresher` doing the redraw.
 */
@Singleton
class WidgetSnapshotWriter @Inject constructor(
    private val snapshots: SnapshotStore,
    private val refresher: WidgetRefresher,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun write(day: DailyLog) {
        val data = day.toWidgetData()
        runCatching { snapshots.save(SnapshotStore.WIDGET, json.encodeToString(data)) }
        refresher.refresh()
    }
}

/** Today's targets minus consumed, rounded — the small blob the widget renders. */
internal fun DailyLog.toWidgetData(): PlateWidgetData = PlateWidgetData(
    remainingKcal = (targets.kcal - totals.kcal).roundToInt(),
    remainingProteinG = (targets.proteinG - totals.proteinG).roundToInt(),
    remainingCarbsG = (targets.carbsG - totals.carbsG).roundToInt(),
    remainingFatG = (targets.fatG - totals.fatG).roundToInt(),
    // Over target once consumption meets the target (mirrors Home's `goalMet`), only when a real
    // target exists — a 0-kcal target (no goal set) shouldn't read as "over".
    overTarget = targets.kcal > 0 && totals.kcal >= targets.kcal,
)
