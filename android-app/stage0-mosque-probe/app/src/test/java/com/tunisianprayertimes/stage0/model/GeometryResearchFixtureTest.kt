package com.tunisianprayertimes.stage0.model

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GeometryResearchFixtureTest {
    @Test fun existingSmallMedianAndLargeAreasShowDifferentStrictFixCapacity() {
        val config = PresenceConfig(strictAccuracyMeters = 30.0, entryMarginMeters = 2.0)
        for (sample in ExistingAreaFixtures.samples) {
            for ((accuracy, expected) in sample.expectedByAccuracy) {
                val assessment = ProbeGeometry.classify(
                    fix = ProbeFix(sample.probeCenter, accuracy, elapsedRealtimeMillis = 10_000),
                    deliveredAtMillis = 10_000,
                    areas = listOf(sample.area),
                    config = config,
                )
                assertEquals("${sample.label}, accuracy=$accuracy", expected, assessment.classification)
                assertEquals(
                    "${sample.label}, supporting ID for accuracy=$accuracy",
                    if (expected == FixClass.STRICT_INSIDE) setOf(sample.area.id) else emptySet<String>(),
                    assessment.insideAreaIds,
                )
            }
        }
    }

    @Test fun generatedCoordinatesArePinnedToTheAuditableJUnitResource() {
        val resource = javaClass.classLoader?.getResourceAsStream("stage0-area-samples.json")
        assertNotNull("Missing JUnit-only geometry resource", resource)
        val bytes = requireNotNull(resource).use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(ExistingAreaFixtures.jsonSha256, actual)
        assertEquals(3, ExistingAreaFixtures.samples.size)
    }
}
