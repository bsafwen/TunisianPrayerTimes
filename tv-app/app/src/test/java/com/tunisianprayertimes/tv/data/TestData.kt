package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.InmPrayerTimes
import java.io.File

/** Canonical inputs the APK bundles, located by paths the Gradle test task passes in. */
internal object TestData {
    private fun dir(property: String): File =
        File(checkNotNull(System.getProperty(property)) { "missing -D$property; run through Gradle" })

    val tvAssets: File get() = dir("tunisianprayertimes.tvAssets")

    val prayerTimes: InmPrayerTimes by lazy {
        InmPrayerTimes.fromParamsJson(File(dir("tunisianprayertimes.dataDir"), "prayer-formula/delegation_params.json").readText())
    }

    val gouvernoratsJson: String by lazy { File(tvAssets, "gouvernorats.json").readText() }

    const val TUNIS = 615
}
