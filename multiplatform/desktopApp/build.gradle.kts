import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

kotlin {
    jvm("java")

    sourceSets {
        val javaMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.animation)
                implementation("net.java.dev.jna:jna:5.16.0")
                implementation("net.java.dev.jna:jna-platform:5.16.0")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.tunisianprayertimes.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "TunisianPrayerTimes"
            packageVersion = (project.findProperty("appVersion")?.toString() ?: "1.0.0").let {
                if (it.count { c -> c == '.' } < 2) "$it.0" else it
            }
            description = "أوقات الصلاة تونس - Tunisian Prayer Times"
            vendor = "TunisianPrayerTimes"

            windows {
                menuGroup = "TunisianPrayerTimes"
                perUserInstall = true
                dirChooser = true
                iconFile.set(project.file("icon.ico"))
            }

            macOS {
                iconFile.set(project.file("icon.icns"))
            }

            linux {
                iconFile.set(project.file("icon.png"))
            }
        }
    }
}
