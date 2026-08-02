plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.maskaccounts"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "org.maskaccounts"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "runtime"
    productFlavors {
        create("runtimeProbe") {
            dimension = "runtime"
            // VirtualApp 0.22.0's Android 12 compatibility depends on legacy target behavior.
            // This sideload-only probe is intentionally separate from the target 36 product.
            targetSdk = 23
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":virtual-runtime"))
    implementation(project(":package-source"))
    implementation(project(":revision-store"))
    implementation(project(":instance-store"))
    testImplementation("junit:junit:4.13.2")
}
