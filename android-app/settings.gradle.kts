pluginManagement {
    // Shared build conventions (bundled offline data), also used by the other Android app.
    includeBuild("../build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TunisianPrayerTimes"
include(":app")

// The Quran's recitations and page scans that Google Play delivers, one on-demand Play Asset
// Delivery pack each (quran-assets/manifest.tsv, written by scripts/quran_assets.py layout).
// Packs whose delivery column says "cdn" come from the quran-cdn Worker alone.
file("quran-assets/manifest.tsv").takeIf { it.isFile }?.readLines().orEmpty()
    .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("pack\t") }
    .map { it.split('\t') }
    .filter { it.getOrNull(5) != "cdn" }
    .map { it[0] }
    .distinct()
    .forEach { pack ->
        include(":$pack")
        project(":$pack").projectDir = file("quran-packs/$pack")
    }

includeBuild("../multiplatform") {
    dependencySubstitution {
        substitute(module("com.tunisianprayertimes:shared")).using(project(":shared"))
    }
}
