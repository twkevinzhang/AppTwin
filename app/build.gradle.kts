plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.maskaccounts"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.maskaccounts"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

dependencies {
    implementation(project(":package-source"))
    implementation(project(":revision-store"))
    implementation(project(":instance-store"))
    testImplementation("junit:junit:4.13.2")
}
