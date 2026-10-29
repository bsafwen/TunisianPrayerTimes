plugins { id("com.android.application") }

android {
    namespace = "com.tunisianprayertimes.stage0"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tunisianprayertimes.stage0"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "stage0-2026-10-28"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = false }
}

// A separately installed feasibility tool, never a production release variant.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-basement:18.9.0")
    implementation("com.google.android.gms:play-services-tasks:18.4.0")
    // Pin the same cached AndroidX generations used by this checkout, not FLP's old minimums.
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.fragment:fragment:1.5.4")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.collection:collection:1.5.0")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime:2.9.4")
    implementation("androidx.savedstate:savedstate:1.3.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.tracing:tracing:1.2.0")
}
