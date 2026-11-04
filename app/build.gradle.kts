plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ruh.flight.live"
    compileSdk = 35
    compileSdkVersion = "android-35"

    defaultConfig {
        applicationId = "com.ruh.flight.live"
        minSdk = 26
        targetSdk = 31
        versionCode = 2
        versionName = "2.0"
    }

    signingConfigs {
        create("keystore") {
            storeFile = file("keystore.jks")
            storePassword = "ruh2026"
            keyAlias = "ruh"
            keyPassword = "ruh2026"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("keystore")
            isMinifyEnabled = false
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
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
