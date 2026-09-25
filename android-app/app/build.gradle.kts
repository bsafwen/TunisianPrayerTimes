import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject
import java.util.Properties

val unsignedRelease = providers.gradleProperty("unsignedRelease")
    .map { it.toBooleanStrict() }
    .getOrElse(false)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (!unsignedRelease && keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

val isReleaseTask = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

fun requireKeystoreProperty(name: String): String {
    return keystoreProperties.getProperty(name)
        ?: throw GradleException("Missing '$name' in ${keystorePropertiesFile.name}.")
}

if (isReleaseTask && !unsignedRelease && !keystorePropertiesFile.exists()) {
    throw GradleException("Missing ${keystorePropertiesFile.path}. Create it before running release builds.")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// Package the canonical announcements for offline calendar browsing without
// maintaining a second hand-copied set of official dates in app/src/main/assets.
abstract class BundleOfficialIslamicDates : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val announcements: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun bundle() {
        fileSystem.sync {
            from(announcements) {
                include("*.json")
                into("official-islamic-dates")
            }
            into(outputDirectory)
        }
    }
}

val bundleOfficialIslamicDates by tasks.registering(BundleOfficialIslamicDates::class) {
    announcements.set(rootProject.layout.projectDirectory.dir("../data/official-islamic-dates"))
    outputDirectory.set(layout.buildDirectory.dir("generated/officialIslamicDatesAssets"))
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundleOfficialIslamicDates, BundleOfficialIslamicDates::outputDirectory)
}

android {
    namespace = "com.tunisianprayertimes"
    compileSdk = 36


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

    defaultConfig {
        applicationId = "com.tunisianprayertimes"
        minSdk = 26
        targetSdk = 36
        versionCode = 155
        versionName = "2.144"
        
        androidResources {
            localeFilters += "ar"
        }
        
        ndk {
            // Target the most common architectures for debug to avoid split issues
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (unsignedRelease) null else signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
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

    kotlinOptions {
        @Suppress("DEPRECATION")
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("com.tunisianprayertimes:shared")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit tests (Robolectric)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.json:json:20240303")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.work:work-testing:2.10.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")

    // Instrumented tests (Compose)
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation(platform("com.google.firebase:firebase-bom:34.12.0"))
    implementation("com.google.firebase:firebase-analytics")
}
