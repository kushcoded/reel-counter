# Reel Counter

An open-source Android app that counts the **Instagram Reels** and **YouTube Shorts** you scroll through, shows a tiny floating counter on top, and keeps a daily history with a limit, watch time, distance scrolled and fun facts. Reels and Shorts are merged into one set of metrics, and each app can be switched off separately.


## Installation guide

1. Open the APK. If Android asks, tap Settings and turn on Allow from this source.

2. When Play Protect blocks it, tap More details → Install anyway. 
If “Install anyway” isn’t shown, open the Play Store → profile picture → Play Protect → ⚙ and turn off Scan apps with Play Protect. Install the app, then turn the scan back on.

3. Open Reel Counter and tap Open Accessibility settings. Find Reel Counter and turn it on. 
If the switch is greyed out or refused (Android 13+), go to Settings → Apps → Reel Counter → ⋮ → Allow restricted settings. Then go back and enable it.

Note: You may have to click on accessability → Reel Counter (greyed out), before the "Allow restricted settings" option appears in Settings → Apps → Reel Counter → ⋮

## Download .apk
https://github.com/kushcoded/reel-counter/releases/latest


<img width="270" height="555" alt="Screenshot_20261019-094532" src="https://github.com/user-attachments/assets/30d40b54-1314-4f94-9cee-fdbf8519dcb4" />
<img width="270" height="555" alt="Screenshot_20261019-094553" src="https://github.com/user-attachments/assets/6d1a35d6-73e2-49ac-a962-1b868c6f3eaa" />

## Privacy
- **No user data is collected.** The app declares **no internet permission** - it cannot send anything anywhere. You can verify this in `app/src/main/AndroidManifest.xml`.
- It uses an Android *Accessibility service* to notice which reel/short is on screen. It only looks at the Instagram and YouTube windows; events from every other app are ignored immediately. No screen content, text or messages are read or stored.
- The only data stored is your daily totals, watch time and settings, on your device.

- ## License
Copyright (C) 2026 kushcoded

Reel Counter is free software, licensed under the [GNU General Public License v3.0](LICENSE). You can use, study, share and modify it, but anyone who distributes a modified version must also release its source under the same license.
