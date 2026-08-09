pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AppTwin"
include(":app")
include(":package-source")
include(":revision-store")
include(":group-store")
include(":application-core")
include(":gms-compat-core")
include(":microg-artifact-source")
include(":gms-runtime-adapter")
include(":virtual-runtime")
include(":runtime-update-fixture")
include(":gms-capability-fixture")
