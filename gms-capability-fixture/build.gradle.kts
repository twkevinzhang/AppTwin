plugins {
    id("com.android.application")
}

android {
    namespace = "org.apptwin.gms.fixture"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.apptwin.gms.fixture"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // Debug-only malicious acceptance code calls AppTwin's direct virtual Binder clients. The
    // classes are supplied by the AppTwin guest-process parent loader and must not be bundled into
    // the fixture APK itself.
    compileOnly(project(":virtual-runtime"))
    implementation("com.google.android.gms:play-services-base:18.10.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("com.google.android.gms:play-services-maps:20.0.0")
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    implementation("com.google.android.gms:play-services-nearby:19.4.0")
    implementation("com.google.firebase:firebase-messaging:25.1.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
