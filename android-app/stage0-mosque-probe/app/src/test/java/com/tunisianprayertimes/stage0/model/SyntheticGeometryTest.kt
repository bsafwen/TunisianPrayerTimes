package com.tunisianprayertimes.stage0.model

import kotlin.math.PI
import kotlin.math.cos
import org.junit.Assert.*
import org.junit.Test

internal fun point(x: Double = 0.0, y: Double = 0.0): GeoPoint =
    GeoPoint(36.8 + y / 111_320.0, 10.2 + x / (111_320.0 * cos(36.8 * PI / 180.0)))

internal fun square(half: Double = 100.0, x: Double = 0.0, y: Double = 0.0): List<GeoPoint> =
    listOf(point(x - half, y - half), point(x + half, y - half), point(x + half, y + half), point(x - half, y + half))

internal fun area(id: String = "synthetic", half: Double = 100.0, x: Double = 0.0, revision: Long = 1): ProbeArea =
    ProbeArea(id, revision, listOf(ProbePolygon(square(half, x))))

class SyntheticGeometryTest {
    private fun assess(x: Double, accuracy: Double = 5.0, areas: List<ProbeArea> = listOf(area())) =
        ProbeGeometry.classify(ProbeFix(point(x), accuracy, 1_000), 1_000, areas).classification

    @Test fun wholeDiskMustClearExteriorAndEntryMargin() {
        assertEquals(FixClass.STRICT_INSIDE, assess(0.0, 30.0))
        assertEquals(FixClass.AMBIGUOUS, assess(95.0, 5.0))
        assertEquals(FixClass.AMBIGUOUS, assess(100.0, 5.0))
        assertEquals(FixClass.AMBIGUOUS, assess(106.0, 5.0))
        assertEquals(FixClass.STRICT_OUTSIDE, assess(111.0, 5.0))
    }

    @Test fun holeCanBeOutsideButItsEdgeRemainsUncertain() {
        val holed = ProbeArea("hole", 1, listOf(ProbePolygon(square(), listOf(square(20.0)))))
        assertEquals(FixClass.STRICT_OUTSIDE, assess(0.0, 5.0, listOf(holed)))
        assertEquals(FixClass.AMBIGUOUS, assess(20.0, 5.0, listOf(holed)))
        assertEquals(FixClass.AMBIGUOUS, assess(25.0, 5.0, listOf(holed)))
        assertEquals(FixClass.STRICT_INSIDE, assess(50.0, 5.0, listOf(holed)))
    }

    @Test fun concaveNotchIsNotAnInsideBoundingBox() {
        val ring = listOf(point(-100.0, -100.0), point(100.0, -100.0), point(100.0, 0.0),
            point(0.0, 0.0), point(0.0, 100.0), point(-100.0, 100.0))
        val shaped = listOf(ProbeArea("concave", 1, listOf(ProbePolygon(ring))))
        fun classify(x: Double, y: Double, accuracy: Double = 5.0) =
            ProbeGeometry.classify(ProbeFix(point(x, y), accuracy, 0), 0, shaped).classification
        assertEquals(FixClass.STRICT_INSIDE, classify(-50.0, 50.0))
        assertEquals(FixClass.STRICT_OUTSIDE, classify(50.0, 50.0))
        assertEquals(FixClass.AMBIGUOUS, classify(5.0, 5.0, 20.0))
    }

    @Test fun outsideRequiresBeingOutsideEveryEnabledArea() {
        val areas = listOf(area("west", 50.0, -200.0), area("east", 50.0, 200.0))
        assertEquals(FixClass.STRICT_INSIDE, assess(200.0, areas = areas))
        assertEquals(FixClass.STRICT_OUTSIDE, assess(0.0, areas = areas))
        assertEquals(FixClass.STRICT_OUTSIDE, assess(200.0, areas = listOf(areas[0], areas[1].copy(enabled = false))))
    }

    @Test fun disjointMultipartWorksWithoutApproximateUnionCoverage() {
        val multi = ProbeArea("multipart", 1, listOf(ProbePolygon(square(40.0, -100.0)), ProbePolygon(square(40.0, 100.0))))
        assertEquals(FixClass.STRICT_INSIDE, assess(-100.0, areas = listOf(multi)))
        assertEquals(FixClass.STRICT_INSIDE, assess(100.0, areas = listOf(multi)))
        assertEquals(FixClass.STRICT_OUTSIDE, assess(0.0, areas = listOf(multi)))
    }

    @Test fun touchingOrOverlappingMultipartIsRejectedInsteadOfApproximated() {
        assertThrows(IllegalArgumentException::class.java) {
            ProbeArea("touch", 1, listOf(ProbePolygon(square(50.0, -50.0)), ProbePolygon(square(50.0, 50.0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeArea("overlap", 1, listOf(ProbePolygon(square(50.0)), ProbePolygon(square(50.0, 30.0))))
        }
    }

    @Test fun crossingAndInvalidHoleGeometryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ProbePolygon(listOf(point(-20.0, -20.0), point(20.0, 20.0), point(-20.0, 20.0), point(20.0, -20.0)))
        }
        assertThrows(IllegalArgumentException::class.java) { ProbePolygon(square(), listOf(square(30.0, 90.0))) }
        assertThrows(IllegalArgumentException::class.java) { ProbePolygon(square(), listOf(square(30.0), square(10.0))) }
    }

    @Test fun poorAccuraciesAreLivenessOnlyThroughOneHundredMeters() {
        assertEquals(FixClass.AMBIGUOUS, assess(0.0, 30.001))
        assertEquals(FixClass.AMBIGUOUS, assess(400.0, 100.0))
        assertEquals(FixClass.UNUSABLE, assess(0.0, 100.001))
        assertEquals(FixClass.UNUSABLE, assess(0.0, Double.NaN))
        assertEquals(FixClass.UNUSABLE, assess(0.0, 0.0))
    }

    @Test fun staleFutureMockAndUnknownQualityCannotMatch() {
        for (fix in listOf(
            ProbeFix(point(), 5.0, -1), ProbeFix(point(), 5.0, 40_001),
            ProbeFix(point(), 5.0, 9_999), ProbeFix(point(), null, 40_000),
            ProbeFix(point(), 5.0, 40_000, isMock = true), ProbeFix(GeoPoint(Double.NaN, 0.0), 5.0, 40_000),
        )) assertEquals(FixClass.UNUSABLE, ProbeGeometry.classify(fix, 40_000, listOf(area())).classification)
        assertEquals(FixClass.STRICT_INSIDE,
            ProbeGeometry.classify(ProbeFix(point(), 5.0, 10_000), 40_000, listOf(area())).classification)
    }

    @Test fun unavailableOrEmptyAreaStoreIsNotOutsideEvidence() {
        val fix = ProbeFix(point(), 5.0, 0)
        assertEquals(FixClass.STORE_UNAVAILABLE, ProbeGeometry.classify(fix, 0, emptyList()).classification)
        assertEquals(FixClass.STORE_UNAVAILABLE, ProbeGeometry.classify(fix, 0, listOf(area()), storeAvailable = false).classification)
        assertEquals(FixClass.STORE_UNAVAILABLE, ProbeGeometry.classify(fix, 0, listOf(area().copy(enabled = false))).classification)
    }
}
