# ScreenPulse Android (Native Background Tracker + Home Screen Widget)

A full-featured native Android application written in Kotlin & Jetpack Compose that measures **OS-wide Screen-On Time in the background**, resets automatically when charging stops (default) or under manual user control, and includes a resizable native Android Home Screen Widget with one-tap reset.

## Features
- **True Background Tracking**: Lightweight Foreground Service listening to `ACTION_SCREEN_ON`, `ACTION_SCREEN_OFF`, `ACTION_POWER_CONNECTED`, `ACTION_POWER_DISCONNECTED`, and `ACTION_TIME_TICK`.
- **Auto-Reset When Charging Stops (Default)**: Immediately archives the previous session and resets Screen-On Time to `00:00:00` when you unplug the charger (configurable minimum battery % threshold or full Manual Only mode).
- **Resettable Anytime**: Reset directly from the app, the persistent status notification, or the native Home Screen Widget.
- **Native Resizable Widget**: Add the **ScreenPulse** widget to your Android launcher (2x1, 2x2, or 4x2) with live Screen-On Time, battery %, active reset rule, and a one-tap Reset button.
- **Survives Reboots**: Automatically restarts after phone reboot via `BootCompletedReceiver`.

## Option A — Build Installable APK Automatically via GitHub (No PC Setup Needed)
1. Create a free repository on GitHub and upload all files from this folder (including `.github/workflows/build-apk.yml`).
2. Open the **Actions** tab in your GitHub repository.
3. Wait ~2 minutes for **Build Android APK** to complete.
4. Download the **ScreenPulse-debug-apk** artifact, unzip it on your phone, and tap `app-debug.apk` to install.

## Option B — Build APK Locally in Android Studio
1. Open this folder in **Android Studio** (Jellyfish / Ladybug or newer).
2. Let Gradle sync finish.
3. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. Transfer `app/build/outputs/apk/debug/app-debug.apk` to your phone and install.
