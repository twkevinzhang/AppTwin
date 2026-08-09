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
include(":virtual-runtime")
include(":runtime-update-fixture")
