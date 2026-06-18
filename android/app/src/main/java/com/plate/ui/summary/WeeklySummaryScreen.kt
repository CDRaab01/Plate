package com.plate.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.data.remote.RangeSummary
import com.plate.ui.components.Caption
import com.plate.ui.components.DataText
import com.plate.ui.components.PanelCard
import com.plate.ui.components.SectionHeader
import com.plate.ui.components.Sparkline
import com.plate.ui.components.StatTile
import com.plate.ui.theme.PlateTheme
import com.plate.util.UiState
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklySummaryScreen(viewModel: WeeklySummaryViewModel = hiltViewModel()) {
    val state by viewModel.summary.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("This week") }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Success -> WeeklySummaryContent(s.data)
                is UiState.Error -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
fun WeeklySummaryContent(summary: RangeSummary) {
    val pulse = PlateTheme.pulse
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        // Calories: hero average + per-day bar series.
        PanelCard {
            Column {
                SectionHeader("Avg calories / day", channel = pulse.calories)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    DataText(
                        summary.averages.kcal.roundToInt().toString(),
                        style = PlateTheme.dataType.dataLarge,
                        color = pulse.calories,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "kcal",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Sparkline(
                    values = summary.days.map { it.totals.kcal.toFloat() },
                    channel = pulse.calories,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    summary.days.forEach { d -> Text(weekdayInitial(d.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }

        // Average macros.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                "Protein", summary.averages.proteinG.roundToInt().toString(),
                modifier = Modifier.weight(1f), unit = "g", channel = pulse.protein,
            )
            StatTile(
                "Carbs", summary.averages.carbsG.roundToInt().toString(),
                modifier = Modifier.weight(1f), unit = "g", channel = pulse.carbs,
            )
            StatTile(
                "Fat", summary.averages.fatG.roundToInt().toString(),
                modifier = Modifier.weight(1f), unit = "g", channel = pulse.fat,
            )
        }

        SectionHeader("Daily breakdown", channel = pulse.carbs)
        summary.days.forEach { day -> DayRow(day.date, day.totals.kcal.roundToInt(), day.targetKcal.roundToInt(), day.trained) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DayRow(date: String, kcal: Int, target: Int, trained: Boolean) {
    PanelCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(weekdayLabel(date), style = MaterialTheme.typography.titleSmall)
                if (trained) Caption("Trained", color = PlateTheme.pulse.protein)
            }
            DataText("$kcal", color = MaterialTheme.colorScheme.onSurface)
            Text(
                " / $target kcal",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun parse(date: String): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()

private fun weekdayInitial(date: String): String =
    parse(date)?.dayOfWeek?.getDisplayName(TextStyle.NARROW, Locale.getDefault()) ?: ""

private fun weekdayLabel(date: String): String =
    parse(date)?.dayOfWeek?.getDisplayName(TextStyle.FULL, Locale.getDefault()) ?: date
