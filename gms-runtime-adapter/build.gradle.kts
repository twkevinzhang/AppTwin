plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.apptwin.gms.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
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
    implementation(project(":gms-compat-core"))
    implementation(project(":microg-artifact-source"))
    implementation(project(":virtual-runtime"))
    testImplementation("junit:junit:4.13.2")
}
