# AutoTapper

An Android utility that automates repeated taps using an Accessibility Service overlay. Place numbered tap targets anywhere on screen, set per-tap delays, then hit play — AutoTapper loops through the sequence indefinitely, injecting taps into whatever app is running underneath.

## How it works

AutoTapper runs as an Android Accessibility Service, which lets it:
- Draw floating overlays without the `SYSTEM_ALERT_WINDOW` permission
- Inject gestures (`GestureDescription`) directly into the foreground app

The overlay stays on top of any app and has two modes:

**Edit mode** — tap the `+` button to drop a numbered pin. Drag pins to reposition them. Use the Prev/Next buttons to select a pin and edit its delay in the delay editor bar. Tap the trash icon to delete the active pin.

**Run mode** — tap `▶` to start. Pins become pass-through (touch events go to the app underneath), and AutoTapper loops through each pin in order, waiting the configured delay before each tap. A pulse animation shows which pin is firing. Tap `■` to stop.

## Features

- Unlimited tap targets per profile, each with an independent delay (ms)
- Draggable delay editor — never occludes the sidebar
- Profiles saved locally to SharedPreferences; load/save from the settings menu
- Link profiles to a specific app — starred (★) in the load list when that app is in the foreground
- Quick Settings tile to toggle the overlay on/off from any screen

## Setup

1. Build and install the APK (Android Studio or `./gradlew installDebug`)
2. Open the app and tap **Open Accessibility Settings**
3. Find **AutoTapper** in the list and enable it
4. The sidebar appears on the right edge of the screen
5. Optionally add the **AutoTapper** tile to your Quick Settings panel

## Requirements

- Android 8.0+ (API 26)
- Accessibility Service permission (prompted on first launch)

## Project structure

```
app/src/main/java/com/autotapper/app/
  GameAutomationService.java   — Accessibility Service: overlay, pins, gesture dispatch, profiles
  AutoTapperTileService.java   — Quick Settings tile
  MainActivity.java            — Launcher screen with link to Accessibility Settings
  TapTarget.java               — Pin data model (x, y, delayMs, index)
```

## Tech notes

- Uses `TYPE_ACCESSIBILITY_OVERLAY` window type — no `SYSTEM_ALERT_WINDOW` permission needed
- Gesture injection via `dispatchGesture()` with a callback chain (not threads) to prevent dropped gestures
- Pins get `FLAG_NOT_TOUCHABLE` during run mode so injected events reach the underlying app
- Profile JSON format: `{"pkg": "com.example.app", "targets": [{x, y, delayMs, index}]}`; old plain-array format is still supported for backward compatibility
