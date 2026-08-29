plugins {
    id("com.android.library")
}

android {
    namespace = "org.apptwin.custodian.contract"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
