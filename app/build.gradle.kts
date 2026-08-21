import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension

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
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.apptwin"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
        compose = true
    }

    defaultConfig {
        applicationId = "org.apptwin"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "runtime"
    productFlavors {
        create("runtimeProbe") {
            dimension = "runtime"
            // Pixel Android 17 rejects targets below 28 and letterboxes legacy activities.
            // Keep the probe isolated from the target 36 product while meeting that floor.
            targetSdk = 28
        }
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
            configure<CrashlyticsExtension> {
                nativeSymbolUploadEnabled = true
                // libva++.so is built in the virtual-runtime library module. Point the
                // uploader at its unstripped NDK output so guest native crashes resolve.
                unstrippedNativeLibsDir = project(":virtual-runtime")
                    .layout.buildDirectory
                    .dir("intermediates/cxx/Debug")
                    .get()
                    .asFile
            }
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
                nativeSymbolUploadEnabled = false
            }
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

    androidResources {
        // The reviewed microG package is copied out of assets on first enable. Keeping it
        // uncompressed avoids a second large in-memory expansion inside the APK reader.
        noCompress += "apk"
    }
}

val pinnedMicrogFileNames = listOf(
    "com.google.android.gms-250932030.apk",
    "com.android.vending-84022630.apk",
)
val generatedRuntimeProbeMicrogAssets = layout.buildDirectory.dir(
    "generated/runtimeProbeMicrogAssets",
)
val prepareRuntimeProbeMicrogAsset by tasks.registering(Sync::class) {
    dependsOn(":microg-artifact-source:preparePinnedMicrogArtifact")
    pinnedMicrogFileNames.forEach { fileName ->
        from(project(":microg-artifact-source").layout.buildDirectory.file(
            "local-cache/$fileName",
        ))
    }
    into(generatedRuntimeProbeMicrogAssets.map { it.dir("microg") })
}

android.sourceSets.getByName("runtimeProbe").assets.srcDir(generatedRuntimeProbeMicrogAssets)

tasks.configureEach {
    if (name.startsWith("mergeRuntimeProbe") && name.endsWith("Assets")) {
        dependsOn(prepareRuntimeProbeMicrogAsset)
    }
    // Lint model generation reads every variant source directory directly instead of going
    // through mergeAssets, so it needs the generated-asset producer in its own task graph.
    if (name.contains("RuntimeProbe") && name.contains("lint", ignoreCase = true)) {
        dependsOn(prepareRuntimeProbeMicrogAsset)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.03.00")
    val firebaseBom = platform("com.google.firebase:firebase-bom:34.16.0")

    implementation(project(":virtual-runtime"))
    implementation(project(":package-source"))
    implementation(project(":revision-store"))
    implementation(project(":group-store"))
    implementation(project(":application-core"))
    implementation(project(":gms-compat-core"))
    implementation(project(":microg-artifact-source"))
    implementation(project(":gms-runtime-adapter"))
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation(firebaseBom)
    debugImplementation("com.google.firebase:firebase-crashlytics-ndk")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
