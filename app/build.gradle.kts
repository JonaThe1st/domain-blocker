plugins {
    id("com.android.application")
}

android {
    namespace = "de.jona.domainblocker"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.jona.domainblocker"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.2.0"
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
