package com.heliossentinel.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarCalculatorTest {

    private data class Ref(
        val place: String,
        val lat: Double,
        val lon: Double,
        val epochMs: Long,
        val azimuth: Double,
        val elevation: Double
    )

    // Reference values generated with pvlib 0.16.1 (NREL SPA, apparent elevation).
    private val references = listOf(
        Ref("Islamabad", 33.6844, 73.0479, 1782025200000L, 167.807, 79.544),
        Ref("Islamabad", 33.6844, 73.0479, 1797823800000L, 130.376, 13.304),
        Ref("Islamabad", 33.6844, 73.0479, 1774007100000L, 256.981, 18.636),
        Ref("Sydney", -33.8688, 151.2093, 1768442400000L, 4.658, 77.243),
        Ref("Quito", -0.1807, -78.4678, 1790183400000L, 264.264, 89.037),
        Ref("Reykjavik", 64.1466, -21.9426, 1782084600000L, 332.760, 1.036),
        Ref("AbuDhabi", 24.4539, 54.3773, 1795500000000L, 142.318, 35.146)
    )

    @Test
    fun sunPositionMatchesNrelSpaWithinHalfADegree() {
        references.forEach { ref ->
            val computed = SolarCalculator.sunPosition(ref.epochMs, GeoLocation(ref.lat, ref.lon))
            val separation = SolarCalculator.angularSeparation(computed, SunPosition(ref.azimuth, ref.elevation))
            assertTrue("${ref.place}: off by $separation° (got $computed)", separation < 0.5)
        }
    }

    @Test
    fun nightTimeIsBelowHorizonAndHasNoServoTarget() {
        // 2026-06-21 20:00 UTC = 01:00 in Islamabad.
        val sun = SolarCalculator.sunPosition(1782072000000L, GeoLocation.ISLAMABAD)
        assertTrue(sun.elevationDeg < 0)
        assertNull(SolarCalculator.servoAnglesFor(sun, panCenterAzimuth = 180.0))
        assertNull(SolarCalculator.trackingError(90.0, 45.0, 1782072000000L, GeoLocation.ISLAMABAD, 180.0))
    }

    @Test
    fun servoAnglesRoundTripToTheSunDirection() {
        val skies = listOf(
            SunPosition(180.0, 40.0), SunPosition(100.0, 10.0), SunPosition(260.0, 70.0),
            SunPosition(10.0, 30.0), SunPosition(350.0, 5.0), SunPosition(45.0, 89.0)
        )
        skies.forEach { sun ->
            val angles = SolarCalculator.servoAnglesFor(sun, panCenterAzimuth = 180.0)
            assertNotNull(angles)
            angles!!
            assertTrue("pan out of range for $sun: $angles", angles.pan in 0.0..180.0)
            assertTrue("tilt out of range for $sun: $angles", angles.tilt in 0.0..180.0)
            val pointing = SolarCalculator.panelDirection(angles.pan, angles.tilt, panCenterAzimuth = 180.0)
            assertEquals("round trip for $sun", 0.0, SolarCalculator.angularSeparation(pointing, sun), 1e-6)
        }
    }

    @Test
    fun northSkyUsesTheTiltFlip() {
        val angles = SolarCalculator.servoAnglesFor(SunPosition(0.0, 30.0), panCenterAzimuth = 180.0)!!
        assertEquals(90.0, angles.pan, 1e-9)
        assertEquals(150.0, angles.tilt, 1e-9)
    }

    @Test
    fun anglesAreClampedToConfiguredLimits() {
        val angles = SolarCalculator.servoAnglesFor(SunPosition(100.0, 10.0), 180.0, minAngle = 20.0, maxAngle = 160.0)!!
        assertEquals(20.0, angles.pan, 1e-9)
        assertEquals(20.0, angles.tilt, 1e-9)
    }

    @Test
    fun wrap180StaysInRange() {
        assertEquals(-170.0, SolarCalculator.wrap180(190.0), 1e-9)
        assertEquals(170.0, SolarCalculator.wrap180(-190.0), 1e-9)
        assertEquals(0.0, SolarCalculator.wrap180(720.0), 1e-9)
        assertEquals(180.0, SolarCalculator.wrap180(180.0), 1e-9)
    }
}
