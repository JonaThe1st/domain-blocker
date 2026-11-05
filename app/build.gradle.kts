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
        versionCode = 6
        versionName = "0.2.1"
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

// No dependencies on purpose: pure platform SDK maximises compatibility.
