plugins {
    `java-gradle-plugin`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Provided at runtime by the builds that apply the plugin.
    compileOnly("com.android.tools.build:gradle-api:9.1.0")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
}

gradlePlugin {
    plugins {
        create("bundledData") {
            id = "tunisianprayertimes.bundled-data"
            implementationClass = "com.tunisianprayertimes.buildlogic.BundledDataPlugin"
        }
    }
}
