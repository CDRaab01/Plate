import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

val keystorePath: String? = System.getenv("KEYSTORE_PATH")

// Sift design-slop audit is wired in only when the sibling Sift repo is checked out next to this
// one (<parent>/{Plate,Sift}); see settings.gradle.kts. CI builds a single repo, so Sift is absent
// and the audit (its test dependency + the DesignSlopTest source under src/test/sift) is skipped,
// keeping :app:testDebugUnitTest green. Locally it runs and gates as usual.
val siftEnabled = rootDir.parentFile?.parentFile?.resolve("Sift")?.exists() == true

android {
    namespace = "com.plate"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.plate"
        minSdk = 26
        targetSdk = 35
        // CI passes VERSION_CODE (the run number) so each signed release installs cleanly over the
        // previous one; defaults to the last shipped value for local/debug builds.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AppAuth redirect receiver scheme (BROKER.md Phase 2c) — com.plate:/oauth2redirect.
        manifestPlaceholders["appAuthRedirectScheme"] = "com.plate"
        buildConfigField(
            "String", "SERVER_URL",
            "\"${localProperties.getProperty("server.url", "https://plate.dragonflymedia.org/")}\""
        )
    }

    signingConfigs {
        // A stable, committed key so every build — debug, local release, CI release — shares one
        // signing identity. New APKs install over the top of existing ones without Android
        // complaining about INSTALL_FAILED_UPDATE_INCOMPATIBLE. Password is not secret.
        create("stable") {
            storeFile = file("plate-debug.keystore")
            storePassword = "plate01"
            keyAlias = "plate"
            keyPassword = "plate01"
        }
        // CI's real release key, only when KEYSTORE_PATH is supplied in the environment.
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            // Prefer CI's release key; fall back to the stable committed key for local releases.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("stable")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    // The Sift audit test (DesignSlopTest) lives in its own source root so it can be excluded —
    // along with its style.sift dependency — whenever the sibling Sift build isn't present.
    if (siftEnabled) {
        sourceSets.getByName("test").java.srcDir("src/test/sift")
    }
}

tasks.withType<Test>().configureEach {
    listOf(
        "roborazzi.test.record",
        "roborazzi.test.verify",
        "roborazzi.test.compare",
    ).forEach { key ->
        (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
    }
    // The Robolectric NATIVE-graphics screenshot tests download a large android-all
    // runtime at test time, which can stall CI. Pass -PexcludeScreenshots to skip them
    // (the gating "Android — Unit Tests" job does this); they still run in the dedicated
    // screenshots job.
    if (project.hasProperty("excludeScreenshots")) {
        filter { excludeTestsMatching("com.plate.screenshot.*") }
    }
}

dependencies {
    // Hilt 2.60 generated code references errorprone annotations at compile time.
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // PULSE design system as a shared library (composite build; see settings.gradle.kts).
    implementation(libs.pulse.ui)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Suite SSO (BROKER.md Phase 2c): OpenID Connect authorization-code + PKCE via AppAuth.
    implementation(libs.appauth)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(libs.datastore.preferences)

    // Room: local mirror of the diary + an offline quick-add queue (CLAUDE.md §2 — match Spotter).
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // Phase 4: on-device barcode scanning (CameraX preview + ML Kit decoder).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)

    // Sift design-slop audit (DesignSlopTest). Resolved from the included Sift build via
    // composite-build dependency substitution (see settings.gradle.kts). Added only when the
    // sibling Sift checkout is present, matching the src/test/sift source set above.
    if (siftEnabled) {
        testImplementation("style.sift:sift-compose:0.1.0")
    }
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
}
