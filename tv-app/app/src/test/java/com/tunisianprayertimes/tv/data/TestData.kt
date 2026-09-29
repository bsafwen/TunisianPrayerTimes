package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.InmPrayerTimes
import java.io.File

/** Inputs the Gradle test task declares and passes in: the files the APK bundles, and the phone catalog. */
internal object TestData {
    private fun path(property: String): File =
        File(checkNotNull(System.getProperty(property)) { "missing -D$property; run through Gradle" })

    val tvAssets: File get() = path("tunisianprayertimes.tvAssets")

    val phoneDhikrCatalog: String by lazy { path("tunisianprayertimes.phoneDhikrCatalog").readText() }

    val prayerTimes: InmPrayerTimes by lazy {
        InmPrayerTimes.fromParamsJson(File(path("tunisianprayertimes.prayerFormulaDir"), "delegation_params.json").readText())
    }

    val gouvernoratsJson: String by lazy { File(tvAssets, "gouvernorats.json").readText() }

    const val TUNIS = 615
}
