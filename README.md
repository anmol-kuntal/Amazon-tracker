# Amazon Tracker (Android)

Polls an Amazon product page in the background and fires an **overlay popup
+ high-priority notification** the instant it's in stock (and, optionally,
at or below a target price).

## What this app does and doesn't do

- ✅ Runs as a **foreground service** so Android won't kill it for battery
  saving, as long as you also grant the battery-optimization exemption in
  the app's setup screen.
- ✅ Draws a **popup over any other app** using `SYSTEM_ALERT_WINDOW`, so
  you'll see the alert even if you're mid-scroll in Instagram.
- ✅ Plays a **looping alarm-stream sound + vibration** for as long as the
  popup is on screen (stops when you tap Open or Dismiss, or after 20s).
  Uses the same audio stream as your phone's wake-up alarm, so it plays
  even with media volume muted — though a phone fully in silent/DND mode
  can still suppress it depending on your settings.
- ✅ Restarts itself automatically after a phone reboot.
- ❌ **Cannot achieve literal zero delay.** Delay = your configured check
  interval + roughly a second to render the popup. Do not set the interval
  below 10 seconds — Amazon will start serving CAPTCHAs to your IP, which
  the app detects and backs off from for 5 minutes.
- ❌ **Will not be accepted on the Play Store.** `SYSTEM_ALERT_WINDOW` plus
  a scraping-based background service violates Play policy. This is a
  sideload-only (install-the-APK-yourself) app, which is fine for personal
  use but means you install it outside the Play Store.
- ❌ Scraping Amazon's page violates their Terms of Service. Use at your
  own risk — Amazon can still block your IP outright regardless of interval.

## Easiest option: let GitHub build the APK for you (no Android Studio)

This project includes a GitHub Actions workflow (`.github/workflows/build-apk.yml`)
that compiles the APK automatically in the cloud.

1. Create a free GitHub account if you don't have one, and create a new
   **private** repo (keep it private - it's your personal tool).
2. Upload this whole `AmazonTracker` folder into that repo (drag-and-drop
   on github.com works, or `git push` if you're comfortable with git).
3. Go to the repo's **Actions** tab. A "Build APK" run should start
   automatically (takes ~3-5 minutes). If it doesn't, click "Build APK" →
   "Run workflow".
4. When it finishes, open the run → scroll to **Artifacts** → download
   `app-debug` (a zip containing `app-debug.apk`).
5. Transfer that `.apk` to your phone (email/Drive/etc.) and tap it to
   install. You'll need to allow "install from unknown sources" for
   whichever app you use to open it.

No local setup, no cable, no Android Studio required.

## Alternative: build locally in Android Studio

1. Install [Android Studio](https://developer.android.com/studio) (free).
2. Open this folder (`AmazonTracker/`) as a project — File → Open.
3. Let Gradle sync (first sync downloads dependencies, takes a few minutes).
4. Plug your Android phone in via USB with **USB debugging** enabled
   (Settings → About phone → tap "Build number" 7 times → Developer options
   → USB debugging), or use Android Studio's built-in emulator to test first.
5. Click the green ▶ Run button, select your device, and it installs directly.

Alternatively, build an installable APK without a cable:
`Build → Generate Signed Bundle / APK → APK`, then transfer the resulting
`.apk` file to your phone (email it to yourself, Google Drive, etc.) and
tap it on the phone to install. You'll need to allow "install from unknown
sources" for whichever app you used to transfer it.

## First-run setup (do this once, in order)

1. Open the app. Tap **Grant notification permission** → allow.
2. Tap **Grant overlay permission** → you'll land in Android settings →
   toggle "Allow display over other apps" for Amazon Tracker → go back.
3. Tap **Disable battery optimization** → choose "Allow" / "Don't optimize".
   On Xiaomi/Oppo/Vivo/OnePlus phones there is *usually an additional*
   manufacturer-specific setting (often called "Autostart" or "Battery
   saver: No restrictions") — check Settings → Apps → Amazon Tracker →
   Battery, and enable autostart if the option exists. This step is the
   #1 reason background trackers get silently killed on these phones.
4. Paste the product URL, optionally a target price, set the check interval
   (15s is a safe default), tap **Start tracking**.
5. You'll see a permanent low-priority notification confirming it's running
   — that's required by Android for any foreground service, it can't be hidden.

## Testing it actually works

Set a target price above the current price temporarily and restart tracking
— you should get the popup + notification within one interval cycle. Once
confirmed, set your real target price (or leave blank to alert on stock only).

## Files

- `MainActivity.kt` — setup screen, requests all three permissions
- `TrackerService.kt` — the foreground service that polls and parses the page
- `OverlayPopupService.kt` — draws the popup window over other apps
- `BootReceiver.kt` — restarts tracking after phone reboot
- `TrackerPrefs.kt` — saves your configured URL/price/interval

## Tracking multiple products

Currently single-product. To track several, either install multiple copies
with different `applicationId` values, or ask for the multi-product version
of this code (a list-based config + a `Map<url, lastState>` in the service)
and it can be added on top of this.
