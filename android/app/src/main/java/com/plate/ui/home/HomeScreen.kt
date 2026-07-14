package com.plate.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.data.remote.AdaptiveTdeeOut
import com.plate.data.remote.DailyLog
import com.plate.data.remote.WeightTrendOut
import design.pulse.ui.components.Caption
import design.pulse.ui.components.ChannelDot
import design.pulse.ui.components.DataText
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.CelebrationPulse
import design.pulse.ui.components.ProgressRing
import design.pulse.ui.components.SectionHeader
import design.pulse.ui.components.Sparkline
import design.pulse.ui.components.StatTile
import design.pulse.ui.components.TickerNumber
import com.plate.ui.theme.PlateTheme
import com.plate.util.UiState
import com.plate.util.UnitSystem
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Whatshot
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onAddFood: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val greeting by viewModel.greeting.collectAsState()
    val nudge by viewModel.mealNudge.collectAsState()
    val unit by viewModel.unitSystem.collectAsState()
    val series by viewModel.weightSeriesKg.collectAsState()

    // Refresh when returning to Home (e.g. after logging a food from search) so the ring is current.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Loading, is UiState.Idle ->
                    EmptyState(icon = Icons.Filled.Refresh, title = "Loading your day…")
                is UiState.Error ->
                    EmptyState(icon = Icons.Filled.Refresh, title = "Couldn't load", subtitle = s.message)
                is UiState.Success -> HomeContent(
                    greeting = greeting,
                    mealNudge = nudge,
                    day = s.data.day,
                    trend = s.data.trend,
                    weightSeriesKg = series,
                    adaptive = s.data.adaptive,
                    unitSystem = unit,
                    onLogWeight = viewModel::logBodyweight,
                    onAddFood = onAddFood,
                )
            }
        }
    }
}

@Composable
internal fun HomeContent(
    greeting: String,
    mealNudge: String,
    day: DailyLog,
    trend: WeightTrendOut?,
    weightSeriesKg: List<Float>,
    adaptive: AdaptiveTdeeOut? = null,
    unitSystem: UnitSystem,
    onLogWeight: (Double) -> Unit,
    onAddFood: () -> Unit,
) {
    val pulse = PlateTheme.pulse
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Greeting + nudge — on the green-led brand gradient so the header carries the app theme.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(pulse.mealGradient)
                .padding(20.dp),
        ) {
            Text(
                greeting,
                style = MaterialTheme.typography.headlineSmall,
                color = androidx.compose.ui.graphics.Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                mealNudge,
                style = MaterialTheme.typography.bodyMedium,
                // Full white (not dimmed) so the nudge clears AA on the deepened gradient.
                color = androidx.compose.ui.graphics.Color.White,
            )
            if (day.trainedToday) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Whatshot,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Caption("Trained today — targets bumped", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }

        // Calorie ring + remaining
        val target = day.targets.kcal
        val consumed = day.totals.kcal
        val remaining = (target - consumed).coerceAtLeast(0.0)
        val progress = if (target > 0) (consumed / target).toFloat() else 0f
        val goalMet = target > 0 && consumed >= target
        PanelCard(Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                SectionHeader("Calories", channel = pulse.calories, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                // When the day's calorie goal is met, wrap the ring in a sustained CelebrationPulse
                // glow — the quiet "you did it" reward. Sustained (not a one-shot burst), so it's
                // safe to render whenever met without edge-detection or re-firing on every visit.
                val ring: @Composable () -> Unit = {
                    ProgressRing(
                        progress = progress,
                        channel = pulse.calories,
                        modifier = Modifier.size(160.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            TickerNumber(
                                target = remaining.roundToInt(),
                                style = PlateTheme.dataType.dataLarge,
                                color = pulse.calories,
                            )
                            Caption(if (goalMet) "goal met" else "kcal left")
                        }
                    }
                }
                if (goalMet) {
                    CelebrationPulse(channel = pulse.calories) { ring() }
                } else {
                    ring()
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MacroRemaining("Protein", day.targets.proteinG - day.totals.proteinG, pulse.protein, Modifier.weight(1f))
                    MacroRemaining("Carbs", day.targets.carbsG - day.totals.carbsG, pulse.carbs, Modifier.weight(1f))
                    MacroRemaining("Fat", day.targets.fatG - day.totals.fatG, pulse.fat, Modifier.weight(1f))
                }
            }
        }

        // Log food — kept directly under the calorie ring (above Weight) so it's reachable without
        // scrolling, the primary action on the Home dashboard.
        Button(onClick = onAddFood, modifier = Modifier.fillMaxWidth()) {
            Text("Log food")
        }

        // Weight trend
        WeightTrendCard(
            trend = trend,
            weightSeriesKg = weightSeriesKg,
            unitSystem = unitSystem,
            onLogWeight = onLogWeight,
        )

        // Adaptive maintenance — sits under the trend since it's derived from weight + intake.
        adaptive?.let { AdaptiveTdeeCard(it) }
    }
}

@Composable
private fun MacroRemaining(label: String, remaining: Double, channel: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    // Standard tile (not dense): keeps the prominent value and lets the "… left" caption wrap
    // rather than ellipsize at one-third width. The ring's TickerNumber is the animated hero.
    StatTile(
        label = "$label left",
        value = remaining.coerceAtLeast(0.0).roundToInt().toString(),
        unit = "g",
        channel = channel,
        modifier = modifier,
    )
}

@Composable
private fun WeightTrendCard(
    trend: WeightTrendOut?,
    weightSeriesKg: List<Float>,
    unitSystem: UnitSystem,
    onLogWeight: (Double) -> Unit,
) {
    val pulse = PlateTheme.pulse
    // Plot the series converted to the user's unit, so the axis matches the readout.
    val plotted = weightSeriesKg.map {
        if (unitSystem == UnitSystem.IMPERIAL) com.plate.util.Units.kgToLb(it.toDouble()).toFloat() else it
    }
    PanelCard(Modifier.fillMaxWidth()) {
        Column {
            SectionHeader(
                "Weight",
                modifier = Modifier.fillMaxWidth(),
                channel = pulse.protein,
                // Latest logged weight (in display units) on the right — current vs the trend below.
                trailing = plotted.lastOrNull()?.let { latest ->
                    {
                        DataText(
                            "${oneDp(latest.toDouble())} ${unitSystem.weightUnit}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            if (plotted.size >= 2) {
                Sparkline(
                    values = plotted,
                    channel = pulse.protein,
                    asBars = false,
                    // Filled-area line (the richer Pulse mode) — area gradient + emphasized last point.
                    strokeWidth = 2.dp,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            if (trend != null) {
                val (statusText, statusColor) = paceLabel(trend.status, pulse)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelDot(statusColor)
                    Spacer(Modifier.width(8.dp))
                    Text(statusText, style = MaterialTheme.typography.bodyMedium, color = statusColor)
                }
                trend.trendWeight?.let { tw ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Trend: ${oneDp(tw)} ${trend.unit}" +
                            (trend.observedRatePerWeek?.let { "  ·  ${signed(it)} ${trend.unit}/wk" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "Log a few weigh-ins to see your trend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            WeighInRow(unitSystem = unitSystem, onLogWeight = onLogWeight)
        }
    }
}

/** Display strings for the adaptive card — pure so it can be unit-tested without Compose. */
internal data class AdaptiveDisplay(val heroKcal: String?, val title: String, val caption: String)

internal fun adaptiveDisplay(a: AdaptiveTdeeOut): AdaptiveDisplay = when (a.status) {
    "active" -> {
        val adj = a.adjustmentKcal.roundToInt()
        AdaptiveDisplay(
            heroKcal = "${a.correctedTdee.roundToInt()} kcal",
            title = when {
                adj > 0 -> "+$adj kcal vs the estimate — you burn more than the formula thought, targets raised."
                adj < 0 -> "$adj kcal vs the estimate — you burn less than the formula thought, targets trimmed."
                else -> "Right on the formula estimate."
            },
            caption = "Learned from ${a.nLoggedDays} of ${a.windowDays} logged days.",
        )
    }
    "learning" -> AdaptiveDisplay(
        heroKcal = null,
        title = "Dialing in your real maintenance…",
        caption = "${a.nLoggedDays} of ${a.minLoggedDays} full days logged — keep logging and weighing in.",
    )
    else -> AdaptiveDisplay(
        heroKcal = null,
        title = "Adaptive targets locked",
        caption = "Log full days plus a few weigh-ins over ~2 weeks and Plate tunes your targets to your real metabolism.",
    )
}

/**
 * "Your maintenance" card (ROADMAP2 T3 #1): once there's enough logged-day + weigh-in history, Plate
 * back-solves the user's real maintenance from energy balance and adjusts targets. Shows the
 * correction when active, otherwise the progress toward unlocking it.
 */
@Composable
private fun AdaptiveTdeeCard(adaptive: AdaptiveTdeeOut) {
    val pulse = PlateTheme.pulse
    val d = adaptiveDisplay(adaptive)
    PanelCard(Modifier.fillMaxWidth()) {
        Column {
            SectionHeader("Maintenance", modifier = Modifier.fillMaxWidth(), channel = pulse.calories)
            Spacer(Modifier.height(12.dp))
            d.heroKcal?.let {
                Text(
                    it,
                    style = PlateTheme.dataType.dataLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                d.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Caption(d.caption)
        }
    }
}

@Composable
private fun WeighInRow(unitSystem: UnitSystem, onLogWeight: (Double) -> Unit) {
    var field by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = field,
            onValueChange = { field = it.filter { ch -> ch.isDigit() || ch == '.' } },
            label = { Text("Weight (${unitSystem.weightUnit})") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                field.toDoubleOrNull()?.let { onLogWeight(it); field = "" }
            },
            enabled = field.toDoubleOrNull() != null,
        ) {
            Text("Log")
        }
    }
}

private fun paceLabel(status: String, pulse: com.plate.ui.theme.PulseColors): Pair<String, androidx.compose.ui.graphics.Color> =
    when (status) {
        "on_pace" -> "On pace" to pulse.protein
        "ahead" -> "Ahead of pace" to pulse.protein
        "behind" -> "Behind pace" to pulse.fat
        else -> "Not enough data yet" to pulse.carbs
    }

private fun oneDp(v: Double): String = ((v * 10).roundToInt() / 10.0).toString()
private fun signed(v: Double): String = (if (v >= 0) "+" else "") + oneDp(v)
