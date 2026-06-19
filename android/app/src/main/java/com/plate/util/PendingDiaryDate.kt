package com.plate.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot hand-off of a requested diary date from the Calendar tab to the Diary tab.
 *
 * The Calendar can't reach the diary-graph-scoped [com.plate.ui.diary.DiaryViewModel] directly, so
 * it parks the requested date here; the DiaryViewModel observes it, jumps to that day, and clears
 * it. Singleton so both sides share one instance — no nav-argument plumbing (which would have to
 * change the diary route and break the existing `popBackStack(DIARY)` flows).
 */
@Singleton
class PendingDiaryDate @Inject constructor() {
    private val _date = MutableStateFlow<String?>(null)
    val date: StateFlow<String?> = _date

    /** Ask the diary to show [date] (ISO yyyy-MM-dd) next time it's observed. */
    fun request(date: String) {
        _date.value = date
    }

    /** Clear the parked date once it's been applied. */
    fun consume() {
        _date.value = null
    }
}
