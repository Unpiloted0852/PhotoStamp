# PhotoStamp

Android app that auto-stamps photos transferred from a camera to the phone
(e.g. via the camera maker's Wi-Fi/Bluetooth app) with date/time, location, barometric elevation, and configurable
METAR-derived weather fields — burned onto a full-quality copy.

## How it works
1. The camera's companion app transfers photos to the phone as usual.
2. A foreground service logs phone **GPS position, barometric pressure, and
   ground speed** at 1 Hz and polls **METAR** (~every 5 min) into in-memory
   ring buffers (nothing is written to disk except the stamped photos).
3. A MediaStore watcher matches each new photo to the logged data **by the
   photo's EXIF capture time** and burns the stamp.

## Data-source rules (no guessing)
The stamp only ever shows real data, sourced honestly:

- **Date / time** — exactly the camera's `DateTimeOriginal`. Local mode shows the
  camera's wall-clock digits verbatim. UTC/Zulu mode converts using the camera's
  `OffsetTimeOriginal` if it recorded one, otherwise the phone's time zone.
- **Coordinates / altitude** — the camera's own GPS EXIF if present; otherwise the
  co-located phone's GPS (the normal case for a camera without GPS).
- **Elevation (MSL)** — barometric: phone pressure + METAR QNH (or a manual QNH).
- **Heading / pitch / roll / yaw** — **camera metadata only**
  (`GPSImgDirection`; XMP gimbal/pose tags). A bare camera that doesn't record
  these shows nothing — they are never taken from the phone.
- **Speed** — phone GPS ground speed (co-located with the camera).
- **Weather** (temp, dew point, RH, wind, visibility, clouds, density altitude) —
  from METAR. **Skipped entirely if no METAR under 75 minutes old is available**
  (e.g. no internet).

Any field whose source has no data is omitted silently.

## Configurable stamp fields
- Date/time: date style, time-zone label (none / abbrev / abbrev+offset / offset /
  UTC-Zulu), 12- or 24-hour, optional seconds.
- Coordinates: decimal / signed / DMS / deg-decimal-min.
- Elevation (MSL), density altitude, QNH + airport microstamp.
- Ground speed: km/h, mph, or knots.
- Compass heading; pitch/roll/yaw (camera metadata only).
- Temperature & dew point (°C/°F), relative humidity, wind, visibility, cloud cover.
- Option to embed the full raw METAR in the photo's `UserComment`.

## Output
- Default: writes `<name>_stamped.jpg` beside the original (original untouched).
- **Replace originals**: overwrites the source file in place. Requires All-files
  access (you'll be prompted). The unstamped original is not kept.
- Either way, **all standard EXIF is preserved** and our additions (GPS, orientation
  normalized, UserComment) are written. Note: manufacturer maker notes are proprietary and
  cannot survive an Android re-encode; standard EXIF tags are carried over.

## Important assumptions / caveats
- The camera clock must match the phone's clock + time zone (matching is by time).
- A barometer is needed for elevation/density altitude; the app runs without one.
- METAR is regional and ~hourly; elevation accuracy is dominated by the phone
  barometer's absolute calibration and by temperature deviation from ISA.
- Notification tap opens the app for quick settings changes.

## Build
Open in Android Studio (Giraffe+); let it sync to fetch the Gradle wrapper and SDK,
then Run. Or from the command line: `./gradlew assembleDebug`.
minSdk 26, targetSdk 34. Sideload / personal use; prebuilt APKs are on the
Releases page.
