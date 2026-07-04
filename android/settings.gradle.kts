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
// the audit stays binary-compatible (same AGP/Kotlin/Compose/Robolectric versions). This assumes
// the three repos sit as siblings under one parent dir: <parent>/{Plate,Spotter,Sift}. CI checks
// out a single repo, so Sift is absent there — include it only when the sibling checkout exists,
// and gate the matching test dependency + source set in app/build.gradle.kts on the same condition,
// so the build stays green with or without Sift. (To gate the audit in CI, publish Sift as a Maven
// artifact and depend on that instead.)
if (file("../../Sift").exists()) {
    includeBuild("../../Sift")
}

// PULSE design system, consumed as a composite build of the sibling Pulse repo
// (<parent>/{Plate,Pulse}); Gradle substitutes the design.pulse:pulse-ui dependency with the
// included build. REQUIRED — the app's theme + generic components live there — so no exists()
// gate; a missing checkout should fail loudly, and CI checks Pulse out next to this repo
// (.github/workflows/{ci,release}.yml). Mirror of Cookbook's settings.gradle.kts.
includeBuild("../../Pulse")
