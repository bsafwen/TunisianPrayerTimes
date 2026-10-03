plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

kotlin {
    jvm("java")

    android {
        namespace = "com.tunisianprayertimes.shared"
        compileSdk = 36
        minSdk = 26

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }

        val javaMain by getting {
            dependencies {
                implementation(compose.desktop.common)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val javaTest by getting {
            // The Python publisher and app parser verify the same correction payload.
            resources.srcDir(rootProject.file("../test-data"))
            // The prayer-formula inputs the Android app bundles.
            resources.srcDir(rootProject.file("../data/prayer-formula"))
            dependencies {
                implementation(kotlin("test"))
            }
        }

        androidMain {
            dependencies {
                implementation("androidx.core:core-ktx:1.16.0")
            }
        }
    }
}

// The prayer-formula golden test compares against the meteo.tn tables scraped into docs/csv.
// Declared as an input so the test reruns when the tables change.
abstract class DocsCsvArgument : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directory: DirectoryProperty

    override fun asArguments() = listOf("-Dtunisianprayertimes.docsCsv=${directory.get().asFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders += objects.newInstance<DocsCsvArgument>().apply {
        directory.set(rootProject.layout.projectDirectory.dir("../docs/csv"))
    }
}
