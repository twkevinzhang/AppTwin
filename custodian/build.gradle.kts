val appVersionName = providers.gradleProperty("APP_VERSION_NAME")
    .orElse("0.1.0-m0")
    .get()
val appVersionCode = providers.gradleProperty("APP_VERSION_CODE")
    .map(String::toInt)
    .orElse(1)
    .get()

val releaseSigningValues = listOf(
    "KEYSTORE_PATH",
    "KEYSTORE_PASSWORD",
    "KEY_ALIAS",
    "KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigningValues.values.all { !it.isNullOrBlank() }

plugins {
    id("com.android.application")
}

android {
    namespace = "org.apptwin.custodian"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.apptwin.custodian"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningValues.getValue("KEYSTORE_PATH")))
                storePassword = requireNotNull(releaseSigningValues.getValue("KEYSTORE_PASSWORD"))
                keyAlias = requireNotNull(releaseSigningValues.getValue("KEY_ALIAS"))
                keyPassword = requireNotNull(releaseSigningValues.getValue("KEY_PASSWORD"))
            }
        }
    }

    buildTypes {
        debug {
            // Uses the same default Android debug keystore as the root AppTwin application.
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":custodian-contract"))
    testImplementation("junit:junit:4.13.2")
}
