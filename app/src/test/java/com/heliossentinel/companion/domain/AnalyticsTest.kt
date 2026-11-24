package com.heliossentinel.companion.domain

import com.heliossentinel.companion.data.model.Reading
import com.heliossentinel.companion.data.model.TrackerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class AnalyticsTest {

    private fun reading(t: Long, x: Float, y: Float, tl: Int, tr: Int, bl: Int, br: Int, mode: TrackerMode = TrackerMode.AUTO) =
        Reading(t, x, y, tl, tr, bl, br, mode)

    // ---- light balance ----
    @Test
    fun lightBalanceSignsMatchDocumentation() {
        val rightHeavy = TrackingAnalytics.lightBalance(100, 300, 100, 300)
        assertEquals(0.5, rightHeavy.horizontal, 1e-9)
        assertEquals(0.0, rightHeavy.vertical, 1e-9)
        val topHeavy = TrackingAnalytics.lightBalance(300, 300, 100, 100)
        assertEquals(0.5, topHeavy.vertical, 1e-9)
        assertEquals(0, TrackingAnalytics.lightBalance(0, 0, 0, 0).total)
    }

    // ---- downsampling ----
    @Test
    fun downsampleKeepsEndpointsAndSize() {
        val items = (0 until 1000).toList()
        val sampled = TrackingAnalytics.downsample(items, 100)
        assertEquals(100, sampled.size)
        assertEquals(0, sampled.first())
        assertEquals(999, sampled.last())
        assertEquals(items.take(5), TrackingAnalytics.downsample(items.take(5), 100))
    }

    // ---- stats ----
    @Test
    fun statsComputeMovementSensorsAndManualShare() {
        val readings = listOf(
            reading(0, 90f, 90f, 100, 200, 300, 400),
            reading(2000, 95f, 85f, 200, 200, 200, 200, TrackerMode.MANUAL),
            reading(4000, 95f, 80f, 300, 200, 100, 0)
        )
        val stats = TrackingAnalytics.stats(readings, location = null, panCenterAzimuth = 180.0)!!
        assertEquals(3, stats.count)
        assertEquals(4000L, stats.spanMs)
        assertEquals(15.0, stats.totalMovementDeg, 1e-6) // 5+5, then 0+5
        assertEquals(100, stats.topLeft.min)
        assertEquals(300, stats.topLeft.max)
        assertEquals(200.0, stats.topLeft.average, 1e-9)
        assertEquals(1.0 / 3, stats.manualFraction, 1e-9)
        assertNull(stats.averageTrackingErrorDeg)
        assertNull(TrackingAnalytics.stats(emptyList(), null, 180.0))
    }

    @Test
    fun trackingErrorIsZeroWhenPanelPointsAtTheSun() {
        val t = 1782025200000L // midday-ish in Islamabad, sun well up
        val sun = SolarCalculator.sunPosition(t, GeoLocation.ISLAMABAD)
        val aim = SolarCalculator.servoAnglesFor(sun, 180.0)!!
        val readings = listOf(reading(t, aim.pan.toFloat(), aim.tilt.toFloat(), 500, 500, 500, 500))
        val stats = TrackingAnalytics.stats(readings, GeoLocation.ISLAMABAD, 180.0)!!
        assertNotNull(stats.averageTrackingErrorDeg)
        assertTrue("error ${stats.averageTrackingErrorDeg}", stats.averageTrackingErrorDeg!! < 0.01)
    }

    // ---- anomalies ----
    @Test
    fun darkSensorsAreReportedOnce() {
        val anomalies = AnomalyDetector.detect(3, 0, 8, 1)
        assertEquals(listOf(AnomalyType.DARK), anomalies.map { it.type })
    }

    @Test
    fun saturatedSensorIsFlagged() {
        val anomalies = AnomalyDetector.detect(4095, 1200, 1300, 1250)
        assertTrue(anomalies.any { it.type == AnomalyType.SATURATED && it.sensor == "Top-left" })
    }

    @Test
    fun singleShadedSensorIsAnOutlier() {
        val anomalies = AnomalyDetector.detect(1000, 1050, 980, 150)
        val outliers = anomalies.filter { it.type == AnomalyType.OUTLIER }
        assertEquals(listOf("Bottom-right"), outliers.map { it.sensor })
    }

    @Test
    fun normalTrackingImbalanceIsNotAnAnomaly() {
        // Sun off to the right: both right sensors brighter — expected, not a fault.
        assertTrue(AnomalyDetector.detect(600, 1400, 620, 1380).isEmpty())
        assertTrue(AnomalyDetector.detect(1000, 1010, 990, 1005).isEmpty())
    }

    // ---- CSV ----
    @Test
    fun csvHasHeaderRowsAndUsesDotDecimals() {
        val readings = listOf(
            reading(1782025200000L, 90.5f, 45.25f, 100, 200, 300, 400, TrackerMode.MANUAL)
        )
        val csv = CsvExporter.toCsv(readings, GeoLocation.ISLAMABAD, 180.0, ZoneOffset.UTC)
        val lines = csv.trimEnd().lines()
        assertEquals(2, lines.size)
        assertEquals(CsvExporter.HEADER.joinToString(","), lines[0])
        val cells = lines[1].split(",")
        assertEquals(CsvExporter.HEADER.size, cells.size)
        assertEquals("2026-06-21T07:00:00Z", cells[0])
        assertEquals("manual", cells[2])
        assertEquals("90.50", cells[3])
        assertEquals("1000", cells[9])
        assertTrue(cells[12].isNotEmpty()) // sun azimuth present with a location
    }

    @Test
    fun csvLeavesSunColumnsBlankWithoutLocation() {
        val csv = CsvExporter.toCsv(listOf(reading(0, 90f, 90f, 1, 2, 3, 4)), null, 180.0, ZoneOffset.UTC)
        val cells = csv.trimEnd().lines()[1].split(",")
        assertEquals("", cells[12])
        assertEquals("", cells[16])
    }
}
