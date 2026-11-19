# Shizutify 🎵✨

**Automatic Ad Skipper & App Restarter via Shizuku**

Shizutify is a lightweight, open-source Android utility that automatically skips audio advertisements on media apps (like Spotify). It actively monitors incoming notifications for specific keywords or regex patterns. Upon detecting an ad, it uses **Shizuku** to forcefully close the app, launch it cleanly in the background, and seamlessly resume playback using native Android media controls—all without requiring root access or turning on your screen.

## ✨ Features

* **Instant Ad Skipping:** Kills and restarts the media app the exact moment an ad notification is posted.
* **Shizuku Powered:** Uses ADB-level privileges to execute `am force-stop` and `am start` commands efficiently.
* **Smart Background Resume:** Utilizes Android's `MediaSessionManager` and `AudioManager` to simulate hardware play button presses, resuming your music perfectly even while the device is locked.
* **Advanced Notification Filtering:** Set custom Regular Expressions (Regex) for both Notification Titles and Text, complete with case-sensitivity toggles.
* **Multi-App Support:** Select exactly which installed applications the service should monitor.
* **Modern Material You UI:** Built with Material 3 components, dynamic theming, and full System Dark Mode compatibility.

## 📱 Prerequisites

1. **Android 8.0+** (API Level 26 or higher).
2. **[Shizuku](https://shizuku.rikka.app/)** must be installed and actively running on your device (can be started via Wireless Debugging, ADB, or Root).

**Note:** Since this app requires Shizuku for some features, the Shizuku service stops automatically in the Official Shizuku App. To prevent this happening, Uninstall the Original Shizuku and Install this [Shizuku Fork](https://github.com/thedjchi/Shizuku) </b> and after installed, then run shizuku normally and make sure that you go to shizuku app, on the top right, click settings icon and click <b>"Watchdog"</b> which prevents Shizuku service from Stopping !!)


## 🚀 Installation & Setup

1. Download the latest `Shizutify-v*.*.*.apk` from the [Releases](../../releases) tab.
2. Install the APK on your device.
3. Open **Shizutify** and tap the large toggle button to grant the required permissions:
   * **Notification Access:** Allows the app to read incoming notifications to detect ads.
   * **Shizuku Permission:** Allows the app to force-close and restart apps.
4. Set your filter rules (Defaults to matching "Advertisement" in the notification title).
5. Select your target apps (e.g., Spotify).
6. Tap the big toggle button again so it reads **SERVICE ACTIVE**.

## 🛠️ How It Works (Technical Overview)

1. **Detection:** `AdSkipNotificationService` (a `NotificationListenerService`) intercepts notifications from your chosen apps.
2. **Validation:** The notification's title and body are evaluated against your configured Regex patterns.
3. **Execution:** If a match is found, the service triggers a Kotlin Coroutine that interfaces with `ShizukuRemoteProcess`.
4. **Restart Pipeline:**
   * Executes `am force-stop <package>` to kill the app and destroy its current ad-playing media session.
   * Executes `am start -n <component>` to cleanly cold-boot the application in the background.
   * Waits exactly 2 seconds for the app's background services to initialize.
   * Dispatches a `KEYCODE_MEDIA_PLAY` event natively via `MediaSessionManager` and `AudioManager` to instantly resume your playlist.

## 💻 Building from Source

This project is built with **Kotlin** and **Gradle**. 

1. Clone the repository:
```bash
   git clone https://github.com/theycallmerubik/Shizutify.git
```

2. Open the project in **Android Studio**.
3. Ensure you are using JDK 17.
4. Sync the project with Gradle files.
5. Build and run:

```bash
./gradlew assembleDebug
```



## 📝 License

This project is licensed under the MIT License - see the [LICENSE](https://github.com/theycallmerubik/Shizutify/blob/master/LICENSE) file for details.

---

*Disclaimer: This app is a utility for automating local device actions via Shizuku. It does not modify, hack, or alter the code of any third-party applications.*
