# Reel Counter (Android)

Counts how many Instagram reels you swipe through each day, keeps a history,
and shows a tiny draggable counter on top of Instagram.

## Build
1. Open this folder in **Android Studio** (Koala or newer) and let Gradle sync.
2. Run on a device (Android 8.0+ / API 26+) or build an APK: *Build > Build APK(s)*.

## First-run setup
1. Open the app and tap **Open Accessibility settings**, then enable **Reel Counter**.
2. Android 13+ (sideloaded apps): if the switch is greyed out, go to
   *App info > ⋮ > Allow restricted settings*, then try again.
3. Open Instagram > Reels and swipe. The pill ("▶ 12") appears while Instagram is in the foreground; drag it anywhere.

## How it works
- An **AccessibilityService** receives scroll events from `com.instagram.android`. Events from other apps are ignored immediately.
- It only counts when the Reels tab is selected / the reels viewer is on screen, and the scrolling view fills most of the screen
  (so comment lists and carousels are skipped). Each step forward to the next reel = +1.
- The pill is an accessibility overlay window, so no "draw over other apps" permission is required.
- Daily totals are stored locally in SharedPreferences (no internet permission is even declared).

## If counting is off
Instagram's internal view ids change between versions. Tune the constants at the bottom of `ReelCounterService.kt`:
`REELS_TAB_IDS`, `VIEWER_IDS`, and the ratios. To find current ids:
`adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml` while a reel is open, then search for `resource-id`.
Use the **−1 / +1** buttons on the Today tab to correct a day by hand.
