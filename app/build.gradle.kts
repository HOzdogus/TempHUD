plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hozdogus.temphud"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hozdogus.temphud"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    // A fixed key committed to the repo, so every CI build is signed the same
    // way and new APKs install over the old one without uninstalling.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
        aidl = true
    }
}

dependencies {
    // Shizuku unlocks FPS, CPU/GPU usage and GPU clock, which Android hides from normal apps.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
