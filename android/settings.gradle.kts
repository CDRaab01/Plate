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
rootProject.name = "Plate"
include(":app")

// Sift design-slop audit (test-only). Consumed via a composite build of the sibling Sift repo so
// the audit stays binary-compatible (same AGP/Kotlin/Compose/Robolectric versions). Assumes the
// three repos remain siblings under one parent dir: <parent>/{Plate,Spotter,Sift}.
includeBuild("../../Sift")
