# FrostByte Power

Free, ad-free Android app that remaps hardware buttons and adds motion
gestures, using an AccessibilityService. No ads, no subscriptions, no
network access, no data collection.

## Features

**Buttons**
- Volume +/− each support single tap, double tap, AND long press — three
  independently configurable actions per key
- Power button remap (Default / Disabled — see OS note below)
- Actions available: Turn Off Screen, Open Power Menu, Toggle Flashlight,
  Take Screenshot, Disabled

**Motion**
- Shake to Wake with 4 sensitivity levels — holds a partial wake lock while
  active so the accelerometer keeps reporting with the screen off (Android
  normally suspends sensor delivery in that state). This is what actually
  makes shake detection work while asleep, at the cost of a small extra
  battery draw versus a sensor with no wake lock. Low-power mode reduces
  the impact by sampling at ~5Hz instead of ~50Hz.
- Wake Up Screen is now a selectable button action, using the same forced
  wake mechanism (`FULL_WAKE_LOCK` + `ACQUIRE_CAUSES_WAKEUP`, held briefly)
  rather than relying on `GLOBAL_ACTION_LOCK_SCREEN`, which is unreliable
  for waking an already-off screen on some OEM builds (notably Samsung)
- Auto-disable shake below a battery threshold (default 15%)

**Power saving**
- Low-power mode: drops the accelerometer sampling rate ~10x while keeping
  shake detection functional
- All sensors are fully unregistered (not just paused) when their feature
  is off — zero battery cost for disabled features
- Vibration feedback toggle (short haptic on every triggered action)

**Display**
- Screen timeout quick-set (15s / 30s / 1m / 2m / Never)

**Quick action tiles**
- Lock screen, open power menu, cycle sound profile (Ring/Vibrate/Silent),
  flashlight, rotation lock, Do Not Disturb, screenshot

**Backup**
- Export/import all settings as a local JSON file (no account, no network)

**Trust & transparency**
- A one-time disclosure screen (shown before requesting Accessibility
  access) explains in plain language exactly what the permission is used
  for and what it does NOT do

**Proximity sensor screen fix (calls)**: on some Samsung devices (One UI),
a physically fine but miscalibrated proximity sensor — e.g. after a screen
replacement — can get stuck reading "covered," which locks the screen off
during calls with no way back if the volume/power buttons don't wake it
either. Confirmed with Samsung support that this OS-level behavior has no
public API to disable directly; two earlier attempts (a wake lock, then a
wake lock + transparent forced-on activity) both failed to override it —
the second one was actively unsafe and was removed after it left the
device stuck behind an unresponsive invisible screen.

The proximity-controlled screen-off only ever applies in **earpiece**
audio mode, by design, on every Android device — it's irrelevant in
speakerphone mode. So instead of fighting the sensor, this feature forces
phone calls and WhatsApp calls into speakerphone the instant they connect
(`READ_PHONE_STATE`, requested at runtime, is used to detect the call
starting). This sidesteps the problem rather than overriding it, and is a
real, working fix for **calls**.

**Known gap, no fix possible without root/adb**: this does not cover
WhatsApp voice **message** playback (listening to a voice note held to
your ear) — that audio route is controlled entirely inside WhatsApp, not
through a system call state, so it can't be forced from outside the app.
Samsung's own "Sensors Off" Quick Settings tile fixes this completely (it
disables the sensor at the OS level) but toggling it programmatically
requires the `WRITE_SECURE_SETTINGS` permission, which Android only grants
via a one-time `adb shell pm grant` command from a computer — there is no
in-app or user-consent path to it. If you ever get access to a computer
once, that's the way to fully automate the "Sensors Off" toggle instead of
flipping it manually from Quick Settings for voice messages.

## Platform notes

**Power button:** Android reserves the physical power key at the OS level.
No app — including this one — can fully intercept or block it on stock
Android. The power button toggle only has an effect on devices/ROMs that
route the key through the accessibility key-event pipeline. This is a
platform limitation, not a bug.

**Sound profile / DND tiles:** require Notification Policy (Do Not
Disturb) access. The app prompts for this the first time you use either
tile if it isn't already granted.

**Rotation lock / screen timeout:** require the "Modify system settings"
special permission (`WRITE_SETTINGS`). The app prompts for this the first
time you use either feature.

**Play Store distribution:** this app uses the Accessibility API for
button/gesture remapping, which is a well-known category Google reviews
closely (their Accessibility API policy exists mainly to stop apps from
using it to read screen content or credentials). The in-app disclosure
screen mirrors what Google's policy asks developers to show, which helps,
but review outcomes are ultimately Google's call, not something fixable in
code. If you're building via GitHub Actions and sideloading the APK
directly, none of this applies to you. Note: as of Aug 31, 2026 Google
requires new Play apps to target Android 16 (API 36) — this project
currently targets API 34, which is fine for F-Droid/sideloading but would
need to be bumped and retested before a Play Store submission.

## F-Droid

This project uses only FOSS (AndroidX/Jetpack) dependencies and standard
Gradle/Kotlin build tools, so it meets F-Droid's core inclusion
requirements. See `LICENSE` for the MIT license. To submit:

1. Tag releases in git (`git tag v1.0.0 && git push --tags`) so F-Droid's
   build server can track versions properly.
2. Read F-Droid's Inclusion Policy: https://f-droid.org/docs/Inclusion_Policy/
3. Submit via a Requests for Packaging issue (easiest, slower — F-Droid
   builds it for you) or a merge request to `fdroiddata` with a metadata
   file you write yourself (faster, more work). See:
   https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/

## How to build the APK (no Android Studio required)

1. Create a new GitHub repository.
2. Upload every file/folder in this project, preserving the folder structure
   (especially `.github/workflows/build.yml` — GitHub only picks up workflows
   from that exact path).
3. Go to the repo's **Actions** tab. The `Build APK` workflow runs
   automatically on every push to `main`, or trigger it manually via
   **Run workflow**.
4. When the run finishes (green check), open it and download the
   `FrostByte-Power-debug-apk` artifact — that's your installable APK.
5. Transfer the APK to your phone and install it (you'll need to allow
   "install unknown apps" for whatever app you use to open it).

## First run on your phone

The app opens to a permission screen. Tap **Enable Accessibility Access**,
find "FrostByte Power" in the Accessibility settings list, and turn it on.
Return to the app — it detects the permission automatically and shows the
main screen.

## Project structure

- `app/src/main/java/com/frostbyte/power/PowerButtonService.kt` — the
  AccessibilityService that intercepts volume key events
- `app/src/main/java/com/frostbyte/power/MainActivity.kt` — onboarding +
  settings UI (Jetpack Compose)
- `app/src/main/java/com/frostbyte/power/PowerPrefs.kt` — stores the chosen
  action per button in SharedPreferences
- `.github/workflows/build.yml` — CI build, produces the APK as a downloadable
  artifact
