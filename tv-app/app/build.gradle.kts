import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty

// Release signing, as for the phone app: CI writes tv-app/keystore.properties from the repository
// secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD). -PunsignedRelease=true
// builds an unsigned release locally, to check that R8 keeps what the app needs.
val unsignedRelease = providers.gradleProperty("unsignedRelease").map { it.toBooleanStrict() }.getOrElse(false)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (!unsignedRelease && keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use(::load)
}
val isReleaseTask = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (isReleaseTask && !unsignedRelease && !keystorePropertiesFile.exists()) {
    throw GradleException("Missing ${keystorePropertiesFile.path}. Create it, or build with -PunsignedRelease=true.")
}

fun requireKeystoreProperty(name: String): String =
    keystoreProperties.getProperty(name) ?: throw GradleException("Missing '$name' in ${keystorePropertiesFile.name}.")

// Play needs a higher version code for every upload: CI passes -PtvVersionCode and -PtvVersionName.
val tvVersionCode = providers.gradleProperty("tvVersionCode").map(String::toInt).getOrElse(1)
val tvVersionName = providers.gradleProperty("tvVersionName").getOrElse("1.0")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    // Bundles data/prayer-formula and data/official-islamic-dates as assets (shared with android-app).
    id("tunisianprayertimes.bundled-data")
}

android {
    namespace = "com.tunisianprayertimes.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tunisianprayertimes.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = tvVersionCode
        versionName = tvVersionName

        androidResources {
            localeFilters += "ar"
        }
    }

    signingConfigs {
        create("release") {
            if (!unsignedRelease && keystorePropertiesFile.exists()) {
                storeFile = file(requireKeystoreProperty("storeFile"))
                storePassword = requireKeystoreProperty("storePassword")
                keyAlias = requireKeystoreProperty("keyAlias")
                keyPassword = requireKeystoreProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Two builds: "play" for Google Play (updated by Play; no update code, as Play requires) and
    // "github" for the many mosque TVs without Play Store, which updates itself from GitHub releases.
    // They have different package names: Play re-signs its builds, so one could not update the other.
    // Installed side by side, the two would take the screen from each other: each looks for the other
    // (OTHER_BUILD, named in the manifest's <queries> for Android 11+) and warns on the kiosk page.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "OTHER_BUILD", "\"com.tunisianprayertimes.tv.github\"")
            manifestPlaceholders["otherBuild"] = "com.tunisianprayertimes.tv.github"
        }
        create("github") {
            dimension = "distribution"
            applicationIdSuffix = ".github"
            buildConfigField("String", "OTHER_BUILD", "\"com.tunisianprayertimes.tv\"")
            manifestPlaceholders["otherBuild"] = "com.tunisianprayertimes.tv"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            signingConfig = if (unsignedRelease) null else signingConfigs.getByName("release")
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

    // Many mosque boxes run Android 8 or 9: a call newer than minSdk must fail the release build
    // (lintVital runs with every release assemble), not crash on those boxes only.
    lint {
        fatal += "NewApi"
    }
}

// JVM unit tests read the canonical inputs the APK bundles, and the app's own sources (to check that
// no religious text is written in them). Declared as inputs so the tests rerun when any of them change.
abstract class TestDataArguments : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val prayerFormula: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tvAssets: DirectoryProperty

    /** Every source set (main, the flavors, debug) with its resources and manifest. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tvSourceSets: DirectoryProperty

    override fun asArguments() = listOf(
        "-Dtunisianprayertimes.prayerFormulaDir=${prayerFormula.get().asFile.absolutePath}",
        "-Dtunisianprayertimes.tvAssets=${tvAssets.get().asFile.absolutePath}",
        "-Dtunisianprayertimes.tvSourceSets=${tvSourceSets.get().asFile.absolutePath}",
    )
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders += objects.newInstance<TestDataArguments>().apply {
        prayerFormula.set(rootProject.layout.projectDirectory.dir("../data/prayer-formula"))
        tvAssets.set(layout.projectDirectory.dir("src/main/assets"))
        tvSourceSets.set(layout.projectDirectory.dir("src"))
    }
}

dependencies {
    implementation("com.tunisianprayertimes:shared")
    // JSON for the dashboard's API and the GitHub release list (the shared module keeps its copy private)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.16.0")

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

    // Coil — image loading for custom backgrounds & announcements
    implementation("io.coil-kt:coil-compose:2.6.0")

    // QR code of the phone-management address (drawn offline, no network)
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
