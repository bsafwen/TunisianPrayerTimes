import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Package canonical JSON from the repository's data/ folder as assets, without
// maintaining hand-copied duplicates in app/src/main/assets (same task as android-app).
abstract class BundleDataAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:Input
    abstract val assetFolder: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun bundle() {
        fileSystem.sync {
            from(source) {
                include("*.json")
                into(assetFolder.get())
            }
            into(outputDirectory)
        }
    }
}

// INM's coordinates and elevations, from which the shared formula computes prayer times offline.
val bundlePrayerFormulaParams by tasks.registering(BundleDataAssets::class) {
    source.set(rootProject.layout.projectDirectory.dir("../data/prayer-formula"))
    assetFolder.set("prayer-formula")
    outputDirectory.set(layout.buildDirectory.dir("generated/prayerFormulaAssets"))
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundlePrayerFormulaParams, BundleDataAssets::outputDirectory)
}

android {
    namespace = "com.tunisianprayertimes.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tunisianprayertimes.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        androidResources {
            localeFilters += "ar"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

// JVM unit tests read the same canonical inputs the APK bundles.
tasks.withType<Test>().configureEach {
    systemProperty("tunisianprayertimes.dataDir", rootProject.file("../data").absolutePath)
    systemProperty("tunisianprayertimes.tvAssets", file("src/main/assets").absolutePath)
}

dependencies {
    implementation("com.tunisianprayertimes:shared")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // TV-specific Compose
    implementation("androidx.tv:tv-foundation:1.0.0-alpha11")
    implementation("androidx.tv:tv-material:1.0.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Leanback (for TV launcher intent category)
    implementation("androidx.leanback:leanback:1.0.0")

    // Coil — image loading for custom backgrounds & announcements
    implementation("io.coil-kt:coil-compose:2.6.0")

    testImplementation("junit:junit:4.13.2")
}
