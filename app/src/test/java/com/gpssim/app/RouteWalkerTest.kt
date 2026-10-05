package com.gpssim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteWalkerTest {

    private val a = LatLng(25.0, 121.0)
    private val b = LatLng(25.0, 121.01) // 約 1008 m 向東
    private val c = LatLng(25.01, 121.01) // 再約 1112 m 向北

    @Test
    fun distanceIsReasonable() {
        val d = GeoUtils.distance(a, b)
        assertEquals(1008.0, d, 5.0)
    }

    @Test
    fun bearingEastAndNorth() {
        assertEquals(90f, GeoUtils.bearing(a, b), 0.1f)
        assertEquals(0f, GeoUtils.bearing(b, c), 0.1f)
    }

    @Test
    fun onceStopsAtEnd() {
        val w = RouteWalker(listOf(a, b, c), speedMps = 10.0, loopMode = LoopMode.ONCE)
        val total = w.pathLength
        val pos = w.advance(total / 10.0 + 100)
        assertTrue(pos.finished)
        assertEquals(c.lat, pos.point.lat, 1e-9)
        assertEquals(c.lng, pos.point.lng, 1e-9)
        assertEquals(total, w.travelled, 1e-6)
    }

    @Test
    fun midpointOfFirstSegment() {
        val w = RouteWalker(listOf(a, b), speedMps = 1.0, loopMode = LoopMode.ONCE)
        val pos = w.advance(w.pathLength / 2)
        assertFalse(pos.finished)
        assertEquals(121.005, pos.point.lng, 1e-6)
        assertEquals(90f, pos.bearing, 0.1f)
    }

    @Test
    fun loopReturnsToStart() {
        val w = RouteWalker(listOf(a, b, c), speedMps = 1.0, loopMode = LoopMode.LOOP)
        val lap = w.pathLength
        val expected = GeoUtils.pathLength(listOf(a, b, c, a))
        assertEquals(expected, lap, 1e-6)
        val pos = w.advance(lap * 2 + 1.0)
        assertFalse(pos.finished)
        assertEquals(a.lat, pos.point.lat, 1e-4)
        assertEquals(a.lng, pos.point.lng, 1e-4)
    }

    @Test
    fun roundTripTurnsBack() {
        val w = RouteWalker(listOf(a, b), speedMps = 1.0, loopMode = LoopMode.ROUND_TRIP)
        val oneWay = GeoUtils.distance(a, b)
        assertEquals(oneWay * 2, w.pathLength, 1e-6)
        val pos = w.advance(oneWay * 1.5)
        assertEquals(121.005, pos.point.lng, 1e-6)
        assertEquals(270f, pos.bearing, 0.1f)
    }

    @Test
    fun speedChangeAndPause() {
        val w = RouteWalker(listOf(a, b), speedMps = 2.0, loopMode = LoopMode.ONCE)
        w.advance(10.0)
        assertEquals(20.0, w.travelled, 1e-9)
        w.advance(0.0)
        assertEquals(20.0, w.travelled, 1e-9)
        w.speedMps = 5.0
        w.advance(2.0)
        assertEquals(30.0, w.travelled, 1e-9)
    }

    @Test
    fun resumesFromStartDistance() {
        val w = RouteWalker(listOf(a, b), speedMps = 1.0, loopMode = LoopMode.ONCE, startDistance = 500.0)
        assertEquals(500.0, w.travelled, 1e-9)
    }
}
