plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ajmal.os"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ajmal.os"
        minSdk = 28
        // Target 28 on purpose: Android 9 behaviour, no package-visibility filtering.
        targetSdk = 28
        // Always increases (minutes since 2024-01-01) so every new build installs over the old one.
        versionCode = ((System.currentTimeMillis() - 1704067200000L) / 60000L).toInt() + 10
        versionName = "1.1.0"
    }

    // One fixed key for every build (local or CI). A random debug key per build is what forced uninstalling.
    signingConfigs {
        create("ajmal") {
            storeFile = file("ajmal.keystore")
            storePassword = "ajmalos123"
            keyAlias = "ajmal"
            keyPassword = "ajmalos123"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ajmal")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("ajmal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Intentionally no dependencies: framework APIs only (Kotlin stdlib is added by the plugin).
