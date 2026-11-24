package com.heliossentinel.companion.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** A point on Earth in decimal degrees (north / east positive). */
data class GeoLocation(val latitude: Double, val longitude: Double) {
    companion object {
        /** FAST-NUCES Islamabad — a sensible default for the DS2001 build. */
        val ISLAMABAD = GeoLocation(33.6844, 73.0479)
    }
}

/** Sun direction: azimuth clockwise from true north, elevation above the horizon. */
data class SunPosition(val azimuthDeg: Double, val elevationDeg: Double) {
    val isAboveHorizon: Boolean get() = elevationDeg > 0.0
}

/** Servo angles (degrees) that point the panel at a given direction. */
data class ServoAngles(val pan: Double, val tilt: Double)

/**
 * Solar position using the NOAA "General Solar Position" equations
 * (accurate to well under 1° for 1900–2100), plus the geometry that maps
 * the Helios Sentinel's two 180° servos onto the sky.
 *
 * Servo geometry (documented so it can be matched to the physical build):
 *  - Pan 90° faces [panCenterAzimuth] (e.g. 180 = due south). Pan sweeps
 *    panCenterAzimuth ± 90°.
 *  - Tilt 0° = horizon on the pan side, 90° = straight up, 180° = horizon on
 *    the opposite side. Tilting past 90° "flips" the panel, which is how two
 *    180° servos can still reach the whole sky.
 */
object SolarCalculator {

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun mod360(d: Double) = ((d % 360.0) + 360.0) % 360.0

    /** Wraps an angle into (-180, 180]. */
    fun wrap180(d: Double): Double {
        val m = mod360(d)
        return if (m > 180.0) m - 360.0 else m
    }

    fun sunPosition(epochMs: Long, location: GeoLocation): SunPosition {
        val jd = epochMs / 86_400_000.0 + 2_440_587.5
        val t = (jd - 2_451_545.0) / 36_525.0

        val l0 = mod360(280.46646 + t * (36_000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(rad(2 * m)) * (0.019993 - 0.000101 * t) +
            sin(rad(3 * m)) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(rad(omega))
        val decl = asin(sin(rad(eps)) * sin(rad(lambda)))

        val y = tan(rad(eps / 2)).let { it * it }
        val eqTimeMin = 4.0 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) +
                4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m))
        )

        val dayFraction = jd + 0.5 - floor(jd + 0.5)
        val minutesUtc = dayFraction * 1440.0
        val trueSolarTime = ((minutesUtc + eqTimeMin + 4.0 * location.longitude) % 1440.0 + 1440.0) % 1440.0
        val hourAngle = if (trueSolarTime / 4.0 < 0) trueSolarTime / 4.0 + 180.0 else trueSolarTime / 4.0 - 180.0

        val latR = rad(location.latitude)
        val cosZen = (sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(rad(hourAngle))).coerceIn(-1.0, 1.0)
        val zenith = acos(cosZen)
        val geometricElevation = 90.0 - deg(zenith)

        val azDenominator = cos(latR) * sin(zenith)
        val azimuth = if (abs(azDenominator) < 1e-9) {
            if (location.latitude > 0) 180.0 else 0.0
        } else {
            val cosArg = (((sin(latR) * cosZen) - sin(decl)) / azDenominator).coerceIn(-1.0, 1.0)
            val a = deg(acos(cosArg))
            if (hourAngle > 0) mod360(a + 180.0) else mod360(540.0 - a)
        }

        return SunPosition(azimuth, geometricElevation + refraction(geometricElevation))
    }

    /** NOAA approximation of atmospheric refraction, in degrees. */
    private fun refraction(elevation: Double): Double {
        if (elevation > 85.0) return 0.0
        val te = tan(rad(elevation))
        val arcSeconds = when {
            elevation > 5.0 -> 58.1 / te - 0.07 / (te * te * te) + 0.000086 / (te * te * te * te * te)
            elevation > -0.575 -> 1735.0 + elevation * (-518.2 + elevation * (103.4 + elevation * (-12.79 + elevation * 0.711)))
            else -> -20.774 / te
        }
        return arcSeconds / 3600.0
    }

    /**
     * Servo angles that aim the panel at [sun], or null if the sun is below
     * the horizon. Angles are clamped to [minAngle]..[maxAngle].
     */
    fun servoAnglesFor(
        sun: SunPosition,
        panCenterAzimuth: Double,
        minAngle: Double = 0.0,
        maxAngle: Double = 180.0
    ): ServoAngles? {
        if (!sun.isAboveHorizon) return null
        val offset = wrap180(sun.azimuthDeg - panCenterAzimuth)
        val raw = if (abs(offset) <= 90.0) {
            ServoAngles(pan = 90.0 + offset, tilt = sun.elevationDeg)
        } else {
            ServoAngles(pan = 90.0 + wrap180(offset - 180.0), tilt = 180.0 - sun.elevationDeg)
        }
        return ServoAngles(raw.pan.coerceIn(minAngle, maxAngle), raw.tilt.coerceIn(minAngle, maxAngle))
    }

    /** Direction the panel is actually facing for the given servo angles. */
    fun panelDirection(pan: Double, tilt: Double, panCenterAzimuth: Double): SunPosition {
        val panAzimuth = panCenterAzimuth + (pan - 90.0)
        return if (tilt <= 90.0) {
            SunPosition(mod360(panAzimuth), tilt)
        } else {
            SunPosition(mod360(panAzimuth + 180.0), 180.0 - tilt)
        }
    }

    /** Great-circle angle in degrees between two sky directions. */
    fun angularSeparation(a: SunPosition, b: SunPosition): Double {
        val cosD = sin(rad(a.elevationDeg)) * sin(rad(b.elevationDeg)) +
            cos(rad(a.elevationDeg)) * cos(rad(b.elevationDeg)) * cos(rad(a.azimuthDeg - b.azimuthDeg))
        return deg(acos(cosD.coerceIn(-1.0, 1.0)))
    }

    /** How far (degrees) the panel is from pointing at the sun; null at night. */
    fun trackingError(
        pan: Double,
        tilt: Double,
        epochMs: Long,
        location: GeoLocation,
        panCenterAzimuth: Double
    ): Double? {
        val sun = sunPosition(epochMs, location)
        if (!sun.isAboveHorizon) return null
        return angularSeparation(panelDirection(pan, tilt, panCenterAzimuth), sun)
    }
}
