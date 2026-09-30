package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.InmPrayerTimes
import java.io.File

/** Inputs the Gradle test task declares and passes in: the files the APK bundles, and the app's sources. */
internal object TestData {
    private fun path(property: String): File =
        File(checkNotNull(System.getProperty(property)) { "missing -D$property; run through Gradle" })

    val tvAssets: File get() = path("tunisianprayertimes.tvAssets")

    /** The app's src folder: every source set (main, play, github, debug…) with its manifest and resources. */
    val tvSourceSets: File get() = path("tunisianprayertimes.tvSourceSets")

    /** The source sets built into the app, without the tests'. */
    val appSourceSets: List<File>
        get() = tvSourceSets.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }.sorted()

    val prayerTimes: InmPrayerTimes by lazy {
        InmPrayerTimes.fromParamsJson(File(path("tunisianprayertimes.prayerFormulaDir"), "delegation_params.json").readText())
    }

    val gouvernoratsJson: String by lazy { File(tvAssets, "gouvernorats.json").readText() }

    const val TUNIS = 615
}
