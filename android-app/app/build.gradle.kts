plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tunisianprayertimes"
    compileSdk = 36

    signingConfigs {
        create("release") {
            storeFile = file("../release-keystore.jks")
            storePassword = "TunisianPrayer2026"
            keyAlias = "tunisian-prayer-times"
            keyPassword = "TunisianPrayer2026"
        }
    }

    defaultConfig {
        applicationId = "com.tunisianprayertimes"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
