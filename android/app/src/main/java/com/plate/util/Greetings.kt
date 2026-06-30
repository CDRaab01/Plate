package com.plate.util

import java.time.LocalTime

/** Time-of-day greeting + meal nudge, shared by the Home and Diary screens. */
object Greetings {
    fun forTime(time: LocalTime, firstName: String? = null): String {
        val base = when (time.hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        return if (firstName.isNullOrBlank()) base else "$base, $firstName"
    }

    fun mealNudge(time: LocalTime): String = when (time.hour) {
        in 5..10 -> "What's for breakfast today?"
        in 11..13 -> "Lunch time — log your midday meal"
        in 14..16 -> "What's for an afternoon snack?"
        in 17..21 -> "Dinner time — what are you having?"
        else -> "Track your meals for today"
    }
}
