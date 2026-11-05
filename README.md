# PhotoStamp

Android app that auto-stamps photos with date/time, location, barometric
elevation and configurable METAR weather fields, burned onto a full-quality copy.
It works for the phone's own camera and for a separate camera whose photos reach
the phone later (e.g. through the camera maker's Wi-Fi/Bluetooth app).

## How it works
1. While **logging**, a foreground service records the phone's **GPS position,
   barometric pressure and ground speed** at 1 Hz and polls **METAR** (~every
   5 min) into in-memory buffers (about 3 hours of logging).
2. A photo watcher picks up each new photo, matches it to the logged data **by
   the photo's EXIF capture time**, and writes a stamped copy.
3. The logged data outlives the logging session (in memory only, nothing is
   written to disk but the stamped photos), so photos transferred after the
   shoot are still matched, as long as the watcher or logger is running.

## Starting logging
- **By hand**: the Start button, the Quick Settings tile, or the Stop button in
  the logging notification.
- **Automatically** (auto-start on): logging starts when any trigger is active
  and stops by itself once they have all cleared for the linger time:
  - the **phone camera opens** (any camera app; face unlock is ignored),
  - a chosen **app is in front**, e.g. the camera's companion app (needs Usage
    access; only while the screen is on),
  - a chosen **Bluetooth device is connected**, e.g. a camera that links to the
    phone for remote control or location (works with the screen off).

  If you stop auto-started logging by hand, it stays off until the trigger clears.

The Status tab's **Setup** list shows exactly which permissions are still
missing for unattended use. The important ones: location *Allow all the time*
(Android withholds location from logging that starts in the background) and
*Battery: Unrestricted*.

## Data-source rules (no guessing)
The stamp only ever shows real data, sourced honestly:

- **Date / time**: exactly the camera's `DateTimeOriginal`. Local mode shows the
  camera's wall-clock digits verbatim. UTC/Zulu mode converts using the camera's
  `OffsetTimeOriginal` if it recorded one, otherwise the phone's time zone.
- **Coordinates / altitude**: the camera's own GPS EXIF if present (needs the
  *Photo locations* permission on Android 10+); otherwise the co-located phone's
  GPS. A phone fix older than 60 s is never used.
- **Elevation (MSL)**: barometric, phone pressure + METAR QNH (or a manual QNH).
- **Heading / pitch / roll / yaw**: **camera metadata only**
  (`GPSImgDirection`; XMP gimbal/pose tags), never taken from the phone.
- **Speed**: phone GPS ground speed (co-located with the camera).
- **Weather** (temp, dew point, RH, wind, visibility, clouds, density altitude):
  from METAR. **Skipped if no report within 75 minutes of the photo exists.**

Any field whose source has no data is omitted silently. By default a photo is
only stamped when logged data covers its capture time, so downloads and old
pictures are never stamped by mistake.

## Matching
- Max gap between capture time and the nearest logged reading (default 30 s).
- **Camera clock offset**: if the camera's clock runs ahead of or behind the
  phone, enter the difference (camera minus phone, in seconds). The Triggers tab
  shows the phone's time with seconds to compare against.
- Optional folder filter.

## Stamp
Live preview (whole photo plus close-up) rendered with the real stamping code.
Size, font, bold, corner position, and shadow or dark-panel background. Fields:
date/time styles and time-zone labels, coordinates in four formats, elevation,
density altitude, QNH + airport, ground speed, heading, pitch/roll/yaw,
temperature, dew point, RH, wind, visibility, clouds. Optionally the full raw
METAR in the photo's `UserComment`.

Photos you pick with **Stamp photos now** are stamped with whatever is known for
them and saved to `Pictures/PhotoStamp`.

## Output
- Default: writes `<name>_stamped.jpg` beside the original (original untouched),
  or in `Pictures/PhotoStamp` if that folder can't take it.
- **Replace originals**: overwrites JPEGs in place (needs All-files access).
  HEIC originals always get a stamped JPEG copy instead.
- All standard EXIF is preserved and GPS / orientation / UserComment are
  written. Manufacturer maker notes can't survive an Android re-encode.

## Updates
PhotoStamp checks this repo's GitHub releases (on app start and every 6 h while
the watcher runs) and installs a newer APK through Android's package installer.
Android asks you to confirm the first update. After that PhotoStamp is the
app's installer of record, and updates can install without a tap while nothing
is logging. Both behaviours can be switched off in the Updates card.

## Caveats
- A barometer is needed for elevation/density altitude; the app runs without one.
- METAR is regional and ~hourly; elevation accuracy is dominated by the phone
  barometer's absolute calibration and by temperature deviation from ISA.

## Build and release
`./gradlew assembleDebug`, or open in Android Studio. minSdk 26, targetSdk 34.

Releases are the debug build, signed with the debug keystore of the machine
that built them (`~/.android/debug.keystore`). Self-updates only work when every
release is signed with the same key, so build releases on the same machine (or
copy that keystore) and back it up. To release: bump `versionCode` and
`versionName` in `app/build.gradle.kts`, build, and attach the APK to a GitHub
release tagged `v<versionName>`.
