package com.reelcounter.app

import java.util.Locale

/** Distance maths and the static "that's about the size of..." fun facts. */
object Distance {
    /** [cmPerReel] is the user-adjustable thumb travel per reel watched. */
    fun meters(reels: Int, cmPerReel: Float): Double = reels * cmPerReel.toDouble() / 100.0
    fun kilometers(reels: Int, cmPerReel: Float): Double = meters(reels, cmPerReel) / 1000.0

    fun formatMeters(m: Double): String = String.format(Locale.getDefault(), "%.1f m", m)

    fun formatKm(km: Double): String = String.format(
        Locale.getDefault(),
        when {
            km < 10 -> "%.3f km"
            km < 100 -> "%.2f km"
            else -> "%.1f km"
        },
        km
    )

    private fun formatDistance(m: Double): String = if (m < 1000) formatMeters(m) else formatKm(m / 1000)

    class FunFact(val text: String, val next: String?)

    private class Landmark(val meters: Double, val phrase: String)

    // Rounded, well-known sizes, smallest to largest.
    private val landmarks = listOf(
        Landmark(0.19, "the length of a pencil"),
        Landmark(0.8, "the length of a skateboard"),
        Landmark(1.7, "the height of an average adult"),
        Landmark(5.5, "the height of a giraffe"),
        Landmark(12.0, "the length of a school bus"),
        Landmark(30.0, "the length of a blue whale"),
        Landmark(100.0, "the length of a football field"),
        Landmark(330.0, "the height of the Eiffel Tower"),
        Landmark(828.0, "the height of the Burj Khalifa"),
        Landmark(8_849.0, "the height of Mount Everest"),
        Landmark(42_195.0, "the distance of a marathon"),
        Landmark(100_000.0, "the height of the edge of space"),
        Landmark(408_000.0, "the height of the International Space Station's orbit"),
        Landmark(6_371_000.0, "the distance from Earth's surface to its centre"),
        Landmark(40_075_000.0, "the distance around the Earth"),
        Landmark(384_400_000.0, "the distance to the Moon"),
    )

    fun funFact(reels: Int, cmPerReel: Float): FunFact {
        val m = meters(reels, cmPerReel)
        val i = landmarks.indexOfLast { it.meters <= m }
        if (i < 0) {
            val first = landmarks.first()
            return FunFact(
                "Watch a few reels and your distance gets compared to real things.",
                "First milestone: ${first.phrase}"
            )
        }
        val lm = landmarks[i]
        val ratio = m / lm.meters
        val text = if (ratio < 1.5) {
            "That's about ${lm.phrase}"
        } else {
            val shown = if (ratio < 10) String.format(Locale.getDefault(), "%.1f", ratio) else String.format(Locale.getDefault(), "%,.0f", ratio)
            "That's about $shown× ${lm.phrase}"
        }
        val next = landmarks.getOrNull(i + 1)?.let { "Next milestone: ${it.phrase} (${formatDistance(it.meters - m)} to go)" }
        return FunFact(text, next)
    }
}
