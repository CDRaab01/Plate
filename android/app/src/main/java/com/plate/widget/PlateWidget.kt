package com.plate.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.plate.MainActivity
import com.plate.data.local.SnapshotStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Hilt can't inject Glance objects; the widget pulls the snapshot store via an EntryPoint. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun snapshotStore(): SnapshotStore
}

private fun entryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

/**
 * The remaining-macros blob the app persists for the widget — today's targets minus consumed.
 * Values may be negative (over target); the widget coerces macro grams at zero for display and
 * colors the calorie hero by sign. Decoded leniently so a future extra field never breaks an old
 * widget.
 */
@Serializable
data class PlateWidgetData(
    val remainingKcal: Int,
    val remainingProteinG: Int,
    val remainingCarbsG: Int,
    val remainingFatG: Int,
    /** Calories consumed have met or passed the day's target (hero goes red). */
    val overTarget: Boolean = false,
)

private val widgetJson = Json { ignoreUnknownKeys = true }

class PlateWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlateWidget()
}

/**
 * The home-screen nutrition glance: today's remaining calories (green under target, red-family over)
 * plus remaining protein / carbs / fat in grams. Reads the app's last-known Home snapshot (no network
 * of its own), so it shows the same truth as the app — offline included. Tap to open Plate.
 */
class PlateWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val raw = entryPoint(context).snapshotStore().read(SnapshotStore.WIDGET)
        val data = raw?.let { runCatching { widgetJson.decodeFromString<PlateWidgetData>(it) }.getOrNull() }
        provideContent {
            GlanceTheme { WidgetBody(data) }
        }
    }
}

// PULSE-adjacent colors, hardcoded: Glance can't consume the Compose theme objects. Values match
// Pulse's palette — ink background, Plate's lead green (PulseGreen), red-family for over-target,
// and the macro channels (protein green, carbs blue, fat orange).
private val InkBg = Color(0xFF0B0D10)      // PulseInk
private val Green = Color(0xFF34D399)      // PulseGreen — Plate's lead accent
private val Red = Color(0xFFFF5C5C)        // PulseRed
private val ProteinC = Color(0xFF34D399)   // PulseGreen
private val CarbsC = Color(0xFF4D7CFF)     // PulseBlue
private val FatC = Color(0xFFFF8A5C)       // PulseOrange
private val TextPrimary = Color(0xFFE7EAF0)
private val TextDim = Color(0xFF9AA3B2)

@Composable
private fun WidgetBody(data: PlateWidgetData?) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(InkBg))
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            "Plate",
            style = TextStyle(color = ColorProvider(Green), fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(6.dp))
        if (data == null) {
            Text(
                "Open Plate to sync",
                style = TextStyle(color = ColorProvider(TextDim), fontSize = 13.sp),
            )
            return@Column
        }
        Text(
            if (data.overTarget) "Calories over" else "Calories left",
            style = TextStyle(color = ColorProvider(TextDim), fontSize = 12.sp),
        )
        Text(
            data.remainingKcal.coerceAtLeast(0).toString(),
            style = TextStyle(
                color = ColorProvider(if (data.overTarget) Red else Green),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(GlanceModifier.height(10.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MacroCell("Protein", data.remainingProteinG, ProteinC)
            Spacer(GlanceModifier.width(12.dp))
            MacroCell("Carbs", data.remainingCarbsG, CarbsC)
            Spacer(GlanceModifier.width(12.dp))
            MacroCell("Fat", data.remainingFatG, FatC)
        }
    }
}

@Composable
private fun MacroCell(label: String, remaining: Int, channel: Color) {
    Column {
        Text(label, style = TextStyle(color = ColorProvider(TextDim), fontSize = 11.sp))
        Text(
            "${remaining.coerceAtLeast(0)}g",
            style = TextStyle(color = ColorProvider(channel), fontSize = 15.sp, fontWeight = FontWeight.Medium),
        )
    }
}
