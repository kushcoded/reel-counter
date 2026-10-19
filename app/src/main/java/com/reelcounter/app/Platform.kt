package com.reelcounter.app

/** The apps whose short-video feeds the service can count. They share one set of metrics: 1 short = 1 reel. */
enum class Platform(val label: String, val appName: String, val pkg: String) {
    Reels("Reels", "Instagram", "com.instagram.android"),
    Shorts("Shorts", "YouTube", "com.google.android.youtube");

    companion object {
        fun fromPackage(pkg: String): Platform? = entries.firstOrNull { it.pkg == pkg }
    }
}
