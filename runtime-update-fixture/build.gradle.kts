plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.apptwin.fixture"
    compileSdk = 36
    ndkVersion = "29.0.13846066"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "org.apptwin.fixture"
        minSdk = 26
        targetSdk = 28
        versionName = "fixture"
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        ndkBuild { path = file("src/mediaCompat/jni/Android.mk") }
    }

    flavorDimensions += "revision"
    productFlavors {
        create("revisionOne") {
            dimension = "revision"
            versionCode = 1
            versionNameSuffix = "-1"
            buildConfigField("int", "FIXTURE_REVISION", "1")
        }
        create("mediaCompat") {
            dimension = "revision"
            applicationId = "org.apptwin.fixture.mediacompat"
            versionCode = 1
            versionNameSuffix = "-media-compat"
            buildConfigField("int", "FIXTURE_REVISION", "1")
        }
        create("revisionTwo") {
            dimension = "revision"
            versionCode = 2
            versionNameSuffix = "-2"
            buildConfigField("int", "FIXTURE_REVISION", "2")
        }
    }

    buildTypes {
        debug {
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
