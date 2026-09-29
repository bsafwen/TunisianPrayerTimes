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

includeBuild("../multiplatform") {
    dependencySubstitution {
        substitute(module("com.tunisianprayertimes:shared")).using(project(":shared"))
    }
}
