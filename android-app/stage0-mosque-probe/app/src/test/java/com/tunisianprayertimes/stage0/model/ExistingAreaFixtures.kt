package com.tunisianprayertimes.stage0.model

/** Generated from stage0-area-samples.json for JUnit only; no runtime resource dependency. */
internal object ExistingAreaFixtures {
    const val jsonSha256 = "916e1df55667ea8cf0ad908994a4e8523d8e9add82c3b35141482bcfe1079aac"
    const val sourceAssetSha256 = "73d18793ea6adb622c46eaace5d8a856d1f47731b1d91f2267b644cb860e5ff1"

    data class Sample(
        val label: String,
        val area: ProbeArea,
        val probeCenter: GeoPoint,
        val expectedByAccuracy: Map<Double, FixClass>,
        val sourceGeometrySha256: String,
    )

    val samples: List<Sample> = listOf(
        Sample(
            label = "small",
            area = ProbeArea(id = "osm/node/9363495324", revision = 1, polygons = listOf(
                ProbePolygon(exterior = listOf(GeoPoint(36.52478800115277, 8.334779364675402), GeoPoint(36.52462258783905, 8.335037083872756), GeoPoint(36.52484505186484, 8.335256278754704), GeoPoint(36.52501046492043, 8.334998560171234), GeoPoint(36.52478800115277, 8.334779364675402)), holes = listOf())
            )),
            probeCenter = GeoPoint(36.524809820684595, 8.335011214488425),
            expectedByAccuracy = linkedMapOf(5.0 to FixClass.STRICT_INSIDE, 10.0 to FixClass.STRICT_INSIDE, 15.0 to FixClass.AMBIGUOUS, 20.0 to FixClass.AMBIGUOUS, 25.0 to FixClass.AMBIGUOUS, 30.0 to FixClass.AMBIGUOUS),
            sourceGeometrySha256 = "c183b89d2460003a3924301590c7d85c23354bb114565076ac9d502f77355299",
        ),
        Sample(
            label = "near_median",
            area = ProbeArea(id = "osm/relation/1385664", revision = 1, polygons = listOf(
                ProbePolygon(exterior = listOf(GeoPoint(35.82708349543061, 10.639303545923772), GeoPoint(35.8265042043709, 10.639518995462305), GeoPoint(35.82666412654447, 10.640167344531168), GeoPoint(35.82724341863964, 10.639951899366437), GeoPoint(35.82708349543061, 10.639303545923772)), holes = listOf())
            )),
            probeCenter = GeoPoint(35.82688850327917, 10.639729982377213),
            expectedByAccuracy = linkedMapOf(5.0 to FixClass.STRICT_INSIDE, 10.0 to FixClass.STRICT_INSIDE, 15.0 to FixClass.STRICT_INSIDE, 20.0 to FixClass.STRICT_INSIDE, 25.0 to FixClass.STRICT_INSIDE, 30.0 to FixClass.AMBIGUOUS),
            sourceGeometrySha256 = "9a3d31ec53db9da9a6e492b6b81c8a46ff75251411790349ada38850c0c5da16",
        ),
        Sample(
            label = "large",
            area = ProbeArea(id = "osm/way/1000321492", revision = 1, polygons = listOf(
                ProbePolygon(exterior = listOf(GeoPoint(36.353990473562554, 10.211112669474145), GeoPoint(36.352664635771355, 10.211112083980263), GeoPoint(36.352664635771355, 10.212640575331566), GeoPoint(36.353990473562554, 10.212641160825447), GeoPoint(36.353990473562554, 10.211112669474145)), holes = listOf())
            )),
            probeCenter = GeoPoint(36.35328455683885, 10.211876603415547),
            expectedByAccuracy = linkedMapOf(5.0 to FixClass.STRICT_INSIDE, 10.0 to FixClass.STRICT_INSIDE, 15.0 to FixClass.STRICT_INSIDE, 20.0 to FixClass.STRICT_INSIDE, 25.0 to FixClass.STRICT_INSIDE, 30.0 to FixClass.STRICT_INSIDE),
            sourceGeometrySha256 = "9c6cacb63fffb52f4de0796180463198eb3979f3b84a18de463f0f06d66a9e56",
        )
    )
}
