# Shield — NSFW Screen Protector for Android 10

Shield is a personal Android app that protects the user from sexually explicit /
NSFW content appearing on the screen, especially inside X (Twitter), Telegram, and
Chrome. It captures the screen locally, analyzes frames on-device with a TFLite
model, and shows a blocking overlay when NSFW content is detected.

> **Target device:** Samsung Galaxy S9, Android 10 (API 29)
> **compileSdk:** 34 · **minSdk:** 29 · **targetSdk:** 34 · **Language:** Kotlin

---

## 1. Build

Requirements (local or CI):
- JDK 17
- Android SDK with `platforms;android-34` and `build-tools;34.0.0`
- Gradle 8.7 (the wrapper is included)

```bash
chmod +x gradlew
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

The included GitHub Actions workflow (`.github/workflows/build-apk.yml`) performs
the same build and uploads the APK as an artifact.

## 2. Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or transfer the APK to the Samsung S9 and install it manually
(Settings → Security → Unknown sources must be allowed).

## 3. Screen Capture permission

When you tap **Start Protection**, the system shows a MediaProjection consent
dialog ("Start capturing everything that's displayed on your screen?"). Tap
**Start now**. This consent is required by Android and cannot be bypassed.

**After every reboot**, Android revokes this consent. Shield will show a
notification reminding you to re-open the app and tap Start Protection again.

## 4. Overlay permission

Shield needs **Display over other apps** (SYSTEM_ALERT_WINDOW) to cover NSFW
content with an overlay. On first start, if the permission is missing, Shield
opens the system settings page for you. Toggle it on for Shield and return.

## 5. Usage Access

To detect which app is in the foreground (for diagnostics and targeting), Shield
uses `UsageStatsManager`, which requires the **Usage Access** permission. This is
a protected permission you grant manually:

**Settings → Digital Wellbeing & parental controls → Usage access → Shield → Allow**

(Exact path varies by device.) If missing on first start, Shield shows a dialog
with a button that opens the right settings page.

> Basic protection (capture → classify → overlay) does **not** depend on Usage
> Access. It is only needed for foreground-app detection and targeting. If you
> skip it, Shield still captures and analyzes the screen; it just can't tailor
> behavior to the current app.

## 6. The AI model

Shield ships with a real, on-device TFLite NSFW model in
`app/src/main/assets/nsfw.tflite` (~5.96 MB).

**Model specification (verified from the model and the reference implementation):**

| Property | Value |
|---|---|
| Input shape | `[1, 224, 224, 3]` |
| Input dtype | `float32` |
| Channel order | **BGR** |
| Preprocessing | Mean subtraction: `B−104, G−117, R−123` (no [0,1] or [−1,1] scaling) |
| Output shape | `[1, 2]` |
| Output dtype | `float32` |
| Output labels | `index 0 = SFW`, `index 1 = NSFW` |

The classifier (`NSFWClassifier.kt`) validates the input and output tensor shapes
at load time and refuses to run if they don't match.

**To replace the model:** put your own `nsfw.tflite` in
`app/src/main/assets/` and update the preprocessing in `NSFWClassifier.kt` to
match your model's spec. Do not guess the preprocessing — inspect the model first.

## 7. Start Protection

1. Open Shield.
2. Tap **Start Protection**.
3. Grant Screen Capture (MediaProjection) consent.
4. The status changes to "الحماية تعمل" and a persistent notification appears.
5. Shield captures at ~3 FPS, classifies each frame on a background thread, and
   shows the overlay when NSFW is detected.

## 8. Create a PIN

On first use, tap **PIN Settings →** enter a PIN → confirm. The PIN is stored
using **PBKDF2WithHmacSHA256** (120,000 iterations, random 16-byte salt,
constant-time comparison). It is never stored in plaintext.

The PIN protects: Stop Protection, Disable Protection, Change Sensitivity, Change
Target Apps, Change PIN, Reset Settings, Delete Logs.

## 9. Test on X

1. Start Protection.
2. Open X (Twitter).
3. Scroll past a known NSFW image or video.
4. The overlay should appear: "تم حجب محتوى غير مناسب".
5. When the content is scrolled away, the overlay lifts.

## 10. Test on Telegram

Same as above — open Telegram, view a chat with NSFW media. The overlay appears
while the content is visible.

## 11. Test video

Shield treats video as a stream of frames. If a single frame is flagged NSFW,
the overlay appears. It stays while the content remains on screen and lifts once
the score drops below the (lower) unblock threshold. No video is recorded or saved.

## 12. Sensitivity modes

| Mode | Block threshold (NSFW score) | Unblock threshold |
|---|---|---|
| Conservative (default) | 0.30 | 0.18 |
| Balanced | 0.50 | 0.30 |
| Less Sensitive | 0.70 | 0.42 |

The gap between block and unblock thresholds is **hysteresis** — it prevents
rapid BLOCK/UNBLOCK flicker while still re-checking the screen continuously.

## 13. Privacy

- **LOCAL ONLY.** No screenshot or frame ever leaves the device.
- No Gemini / OpenAI / Cloud Vision / external server calls.
- No screenshots or frames are saved to disk.
- Only metadata is kept in-memory for diagnostics: timestamp, package name,
  score, action. This is cleared when the service stops.

## 14. Strict Protection Mode

When enabled, all protection-affecting settings require the PIN:
- Stop / disable protection
- Change sensitivity / threshold
- Change target apps
- Change PIN
- Disable strict mode itself

Shield **does not** claim to prevent Android's own Force Stop or Uninstall —
that is impossible with official APIs. Only official APIs are used.

---

## LIMITATIONS

These are real platform constraints, not missing features:

1. **MediaProjection consent is revoked on reboot.** Android does not allow
   retaining the capture token across reboots. Shield shows a notification after
   boot reminding you to re-grant consent. Auto-capture after reboot is not
   possible without violating Android's security model.

2. **MediaProjection cannot be started from a background context.** The initial
   consent must be requested from an Activity (MainActivity does this). This is
   an Android requirement, not a design choice.

3. **No Face Recognition.** Shield does not detect or identify people. It only
   classifies image content as SFW/NSFW.

4. **The model is an image classifier, not a perfect detector.** It may produce
   false positives (especially on skin-heavy but non-sexual images like beach
   scenes, medical content, or art) and false negatives. The sensitivity modes
   let you trade off between the two. Conservative mode errs toward blocking.

5. **Overlay vs. Force Stop.** The overlay covers content while the app is on
   screen, but it does not and cannot prevent the user from force-stopping or
   uninstalling Shield via Android system settings. No official API allows that.

6. **Usage Access is a manual permission.** Android does not allow granting
   `PACKAGE_USAGE_STATS` programmatically. Shield guides you to the settings page.

7. **Performance on Samsung Galaxy S9.** Capture is capped at ~360px width and
   ~3 FPS to keep CPU and battery use modest. Inference runs on a background
   thread. On the S9's Exynos/Snapdragon, typical inference is well under 100ms
   per frame for this small model.
