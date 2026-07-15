package com.plate.ui.metabolism

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.data.remote.AdaptiveTdeeOut
import com.plate.ui.home.adaptiveDisplay
import com.plate.ui.theme.PlateTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import design.pulse.ui.theme.Pulse
import kotlin.math.roundToInt

/**
 * The metabolism dashboard (ROADMAP3 Plate flagship): a full-screen, MacroFactor-style read of the
 * adaptive-TDEE engine — the maintenance Plate observed from your data vs the formula estimate, why
 * your targets moved, how confident it is, and (before it unlocks) the progress to get there. All
 * from `GET /goals/adaptive`; presentation only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetabolismScreen(
    onBack: () -> Unit,
    viewModel: MetabolismViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your metabolism") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is MetabolismState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is MetabolismState.NoGoal -> EmptyState(
                    icon = Icons.Filled.Info,
                    title = "No goal yet",
                    subtitle = "Set a goal and Plate starts learning your real maintenance from your logs and weigh-ins.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                is MetabolismState.Error -> EmptyState(
                    icon = Icons.Filled.Refresh,
                    title = "Couldn't load your metabolism",
                    subtitle = "Check your connection and try again.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                is MetabolismState.Data -> MetabolismContent(s.adaptive)
            }
        }
    }
}

@Composable
internal fun MetabolismContent(adaptive: AdaptiveTdeeOut) {
    val calories = PlateTheme.pulse.calories
    val display = adaptiveDisplay(adaptive)
    val active = adaptive.status == "active"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Hero: the number that matters (your maintenance) once we trust it, else what it's doing.
        PanelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Caption(if (active) "Your maintenance" else "Adaptive maintenance")
                Spacer(Modifier.height(6.dp))
                if (active) {
                    DataText(
                        "${adaptive.correctedTdee.roundToInt()}",
                        style = PlateTheme.dataType.dataLarge,
                        color = calories,
                    )
                    Caption("kcal / day")
                } else {
                    Text(
                        display.title,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (active) {
            // The correction itself: formula estimate → what your data revealed.
            PanelCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionHeader("The correction", channel = calories)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Caption("Formula estimate")
                            Spacer(Modifier.height(2.dp))
                            DataText(
                                "${adaptive.formulaTdee.roundToInt()}",
                                style = PlateTheme.dataType.dataMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "→",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Caption("Observed")
                            Spacer(Modifier.height(2.dp))
                            DataText(
                                "${adaptive.correctedTdee.roundToInt()}",
                                style = PlateTheme.dataType.dataMedium,
                                color = calories,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        display.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Confidence, the way a serious tool shows it.
            PanelCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionHeader("Confidence", channel = calories)
                    Spacer(Modifier.height(12.dp))
                    Meter(adaptive.confidence.toFloat().coerceIn(0f, 1f), calories)
                    Spacer(Modifier.height(8.dp))
                    Caption(
                        "${confidenceLabel(adaptive.confidence)} · learned from " +
                            "${adaptive.nLoggedDays} of ${adaptive.windowDays} logged days",
                    )
                }
            }
        } else {
            // Not unlocked yet: show progress toward it.
            PanelCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionHeader("Unlock progress", channel = calories)
                    Spacer(Modifier.height(12.dp))
                    val frac = if (adaptive.minLoggedDays > 0) {
                        (adaptive.nLoggedDays.toFloat() / adaptive.minLoggedDays).coerceIn(0f, 1f)
                    } else 0f
                    Meter(frac, calories)
                    Spacer(Modifier.height(8.dp))
                    Caption(display.caption)
                }
            }
        }

        // Educational: how the number is derived — the trust-builder MacroFactor leans on.
        PanelCard(Modifier.fillMaxWidth()) {
            Column {
                SectionHeader("How Plate learns this", channel = calories)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your true maintenance is whatever holds your weight steady. Plate back-solves it " +
                        "from energy balance: over a rolling window it compares everything you logged " +
                        "with how your weight actually trended, and infers the calories your body really " +
                        "burns — no formula can know that in advance. The more full days you log and the " +
                        "more you weigh in, the more it trusts the observed number over the estimate.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun confidenceLabel(c: Double): String = when {
    c >= 0.66 -> "High confidence"
    c >= 0.33 -> "Medium confidence"
    else -> "Low confidence"
}

/** A thin meter: a hairline track with a channel-filled portion (matches the Home card). */
@Composable
private fun Meter(fraction: Float, channel: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Pulse.structure.hairlineStrong),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(channel),
        )
    }
}
