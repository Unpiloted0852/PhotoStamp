package com.photostamp.app

import androidx.exifinterface.media.ExifInterface

/**
 * Reads ONLY what the camera itself encoded into the photo. Anything absent is
 * returned as null/NaN so the caller can omit it silently (never guessed).
 *
 * Heading comes from GPSImgDirection (present only if a GPS/compass accessory
 * recorded it). Pitch/roll/yaw are not standard EXIF — they are read from XMP if
 * a rig/drone wrote them (DJI Gimbal/Flight, GPano Pose, Camera namespace).
 */
data class CameraMeta(
    val lat: Double?,
    val lon: Double?,
    val altM: Double?,
    val headingDeg: Double?,
    val headingRef: String?,   // "T" or "M"
    val pitch: Double?,
    val roll: Double?,
    val yaw: Double?,
    val offsetMinutes: Int?,   // OffsetTimeOriginal as minutes east of UTC
) {
    companion object {
        fun read(exif: ExifInterface): CameraMeta {
            val ll = exif.latLong   // null unless camera encoded GPS
            val lat = ll?.get(0)
            val lon = ll?.get(1)

            val alt = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE)?.let {
                val a = exif.getAltitude(Double.NaN); if (a.isNaN()) null else a
            }

            // Show a genuine bearing. A compass-less camera emits a placeholder of
            // exactly 0 with no reference; treat only that case as "no heading".
            val hRef = exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF)
            val hStr = exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION)
            val hVal = if (hStr.isNullOrBlank()) Double.NaN
                else exif.getAttributeDouble(ExifInterface.TAG_GPS_IMG_DIRECTION, Double.NaN)
            val heading: Double? = when {
                hVal.isNaN() -> null
                hVal != 0.0 -> hVal
                !hRef.isNullOrBlank() -> hVal   // exactly north, but genuinely recorded
                else -> null                    // 0 with no reference = placeholder
            }

            val xmp = runCatching {
                exif.getAttributeBytes(ExifInterface.TAG_XMP)?.toString(Charsets.UTF_8)
            }.getOrNull()
            val pitch = xmp?.let { firstNum(it, PITCH_KEYS) }
            val roll = xmp?.let { firstNum(it, ROLL_KEYS) }
            val yaw = xmp?.let { firstNum(it, YAW_KEYS) }

            val off = parseOffset(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))

            return CameraMeta(lat, lon, alt, heading, hRef, pitch, roll, yaw, off)
        }

        private val PITCH_KEYS = listOf("GimbalPitchDegree", "FlightPitchDegree", "PosePitchDegrees", "Pitch")
        private val ROLL_KEYS = listOf("GimbalRollDegree", "FlightRollDegree", "PoseRollDegrees", "Roll")
        private val YAW_KEYS = listOf("GimbalYawDegree", "FlightYawDegree", "Yaw")

        /**
         * Extract a numeric value for one of the given XMP keys. The lookbehind
         * anchors on a name boundary so a bare "Pitch" matches "Camera:Pitch" or
         * "exif:Pitch" but NOT the middle of "GimbalPitchDegree".
         */
        private fun firstNum(xmp: String, keys: List<String>): Double? {
            for (k in keys) {
                val key = Regex.escape(k)
                val attr = Regex("(?<![A-Za-z])$key\\s*=\\s*\"\\s*([-+]?\\d+(?:\\.\\d+)?)").find(xmp)
                val elem = Regex("(?<![A-Za-z])$key>\\s*([-+]?\\d+(?:\\.\\d+)?)").find(xmp)
                val v = (attr ?: elem)?.groupValues?.get(1)?.toDoubleOrNull()
                if (v != null) return v
            }
            return null
        }

        /** "+02:00" / "-05:30" / "Z" -> minutes east of UTC. */
        private fun parseOffset(s: String?): Int? {
            if (s.isNullOrBlank()) return null
            val t = s.trim()
            if (t.equals("Z", true)) return 0
            val m = Regex("([+-])(\\d{2}):?(\\d{2})").find(t) ?: return null
            val sign = if (m.groupValues[1] == "-") -1 else 1
            return sign * (m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt())
        }
    }
}
