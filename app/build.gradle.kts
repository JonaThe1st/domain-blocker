plugins {
    id("com.android.application")
}

android {
    namespace = "de.jona.domainblocker"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "de.jona.domainblocker"
        minSdk = 26
        targetSdk = 34
        versionCode = 36
        versionName = "0.4.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideload build; debug key keeps install friction-free.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Kotlin-stdlib only: Kotlin classes reference kotlin.jvm.internal.* at
// runtime; without it the process dies during class loading (before any
// app code or exception handler can run). No androidx/material.
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
}
