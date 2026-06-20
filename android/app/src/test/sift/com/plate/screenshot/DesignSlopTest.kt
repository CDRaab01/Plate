package com.plate.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.plate.ui.about.AboutContent
import com.plate.ui.navigation.PlateBottomBar
import com.plate.ui.theme.PlateTheme
import style.sift.compose.DesignSlopSuite
import style.sift.compose.TokenScan
import style.sift.core.config.SiftConfig
import java.io.File

/**
 * Sift design-slop audit for Plate (CLAUDE.md §7 of the Sift repo). Reuses the exact scene helpers
 * the Roborazzi [ScreenshotTest] renders — same `*Content` composables, same sample data — so the
 * audit and the screenshots never drift. The inherited `audit()` renders each scene on Robolectric
 * NATIVE graphics, runs the rule catalog, writes `build/sift/report.json`, and fails on any
 * error-severity finding (low-contrast-text / tiny-touch-target).
 *
 * Tokens are scanned from Plate's theme package so the palette/font rules see the real design
 * intent. Config is read from `.sift/config.json` (module-relative), falling back to defaults.
 */
class PlateDesignSlopTest : DesignSlopSuite(
    config = SiftConfig.fromFileOrDefault(),
    tokens = TokenScan.scan(listOf(File("src/main/java/com/plate/ui/theme"))),
) {
    init {
        register("login") { LoginScene() }
        register("register") { RegisterScene() }
        register("diary") { DiaryScene() }
        register("search") { SearchScene() }
        register("coach") { CoachScene() }
        register("recipes") { RecipesScene() }
        register("about") { AboutContent(onBack = {}) }
        register("bottom_bar") { PlateBottomBar(currentDestination = null, onSelect = {}) }
    }

    /** Register a scene in both dark and light, themed + backed by a themed Surface like MainActivity. */
    private fun register(name: String, content: @Composable () -> Unit) {
        scene(name, dark = true) { Themed(dark = true, content) }
        scene(name, dark = false) { Themed(dark = false, content) }
    }
}

@Composable
private fun Themed(dark: Boolean, content: @Composable () -> Unit) {
    PlateTheme(darkTheme = dark) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
