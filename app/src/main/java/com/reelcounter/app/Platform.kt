package com.reelcounter.app

/** The short-video feeds the app can count. Each has its own totals, watch time and daily limit. */
enum class Platform(val label: String, val noun: String, val appName: String, val pkg: String) {
    Reels("Reels", "reels", "Instagram", "com.instagram.android"),
    Shorts("Shorts", "shorts", "YouTube", "com.google.android.youtube");

    val store: CountStore get() = ReelRepository.store(this)

    /** "reel" / "short" */
    val singular: String get() = noun.removeSuffix("s")

    companion object {
        fun fromPackage(pkg: String): Platform? = entries.firstOrNull { it.pkg == pkg }
    }
}
