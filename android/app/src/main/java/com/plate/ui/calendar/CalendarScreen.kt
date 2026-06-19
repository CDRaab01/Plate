package com.plate.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.plate.data.remote.DaySummary
import com.plate.ui.components.DataText
import com.plate.ui.components.PanelCard
import com.plate.ui.components.SectionHeader
import com.plate.ui.components.StatTile
import com.plate.ui.theme.PlateTheme
import com.plate.util.UiState
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onOpenDay: () -> Unit = {},
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val displayedMonth by viewModel.displayedMonth.collectAsState()
    val monthData by viewModel.monthData.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val currentMonth = YearMonth.now()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Calendar") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Month navigation header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.prevMonth() }) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous month")
                }
                Text(
                    text = displayedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    onClick = { viewModel.nextMonth() },
                    enabled = displayedMonth.isBefore(currentMonth),
                ) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next month")
                }
            }

            // Day-of-week header
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            when (val state = monthData) {
                is UiState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is UiState.Error -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }

                is UiState.Success -> {
                    val dayMap: Map<LocalDate, DaySummary> = state.data.associateBy {
                        LocalDate.parse(it.date)
                    }

                    MonthGrid(
                        month = displayedMonth,
                        dayMap = dayMap,
                        selectedDate = selectedDate,
                        onDayClick = { viewModel.selectDate(it) },
                    )

                    // Month average panel — only shown when at least one day has data
                    val loggedDays = state.data.filter { it.totals.kcal > 0.0 }
                    if (loggedDays.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        MonthAveragePanel(loggedDays)
                    }

                    // Selected-day detail card
                    val selected = selectedDate
                    if (selected != null) {
                        Spacer(Modifier.height(8.dp))
                        DayDetailCard(
                            date = selected,
                            summary = dayMap[selected],
                            onOpen = {
                                viewModel.requestDay(selected)
                                onOpenDay()
                            },
                        )
                    }
                }

                else -> Unit
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    dayMap: Map<LocalDate, DaySummary>,
    selectedDate: LocalDate?,
    onDayClick: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    // dayOfWeek: Mon=1..Sun=7 → Sunday-first grid offset Mon=1, Tue=2, ..., Sun=0
    val firstDay = month.atDay(1).dayOfWeek.value % 7
    val daysInMonth = month.lengthOfMonth()
    val cells: List<Int?> = List(firstDay) { null } + List(daysInMonth) { it + 1 }
    val remainder = cells.size % 7
    val paddedCells = if (remainder == 0) cells else cells + List(7 - remainder) { null }

    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        paddedCells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    if (day != null) {
                        val date = month.atDay(day)
                        DayCell(
                            day = day,
                            isToday = date == today,
                            isSelected = date == selectedDate,
                            isFuture = date.isAfter(today),
                            summary = dayMap[date],
                            onClick = { onDayClick(date) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    isToday: Boolean,
    isSelected: Boolean,
    isFuture: Boolean,
    summary: DaySummary?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = PlateTheme.pulse
    val hasData = (summary?.totals?.kcal ?: 0.0) > 0.0
    val trained = summary?.trained == true

    val circleColor = when {
        isSelected -> pulse.carbs
        isToday -> pulse.carbs.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val textColor = when {
        isSelected -> pulse.onCarbs
        isFuture -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    // Trained (green) takes priority over food-logged (violet) — mirrors Spotter's
    // "completed workout = green" semantic.
    val dotColor = when {
        trained -> pulse.protein
        hasData -> pulse.calories
        else -> Color.Transparent
    }

    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(enabled = !isFuture, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(color = circleColor, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DataText(
                text = "$day",
                style = PlateTheme.dataType.numeral.copy(textAlign = TextAlign.Center),
                color = textColor,
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = dotColor, shape = CircleShape),
        )
    }
}

@Composable
private fun MonthAveragePanel(loggedDays: List<DaySummary>) {
    val pulse = PlateTheme.pulse
    val avgKcal = loggedDays.map { it.totals.kcal }.average()
    val avgProtein = loggedDays.map { it.totals.proteinG }.average()
    val avgCarbs = loggedDays.map { it.totals.carbsG }.average()
    val avgFat = loggedDays.map { it.totals.fatG }.average()

    PanelCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column {
            SectionHeader(
                label = "${loggedDays.size}-day average",
                channel = pulse.calories,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                MacroAvgColumn(
                    label = "Kcal",
                    value = avgKcal.roundToInt().toString(),
                    channel = pulse.calories,
                    modifier = Modifier.weight(1f),
                )
                MacroAvgColumn(
                    label = "Protein",
                    value = "${avgProtein.roundToInt()}g",
                    channel = pulse.protein,
                    modifier = Modifier.weight(1f),
                )
                MacroAvgColumn(
                    label = "Carbs",
                    value = "${avgCarbs.roundToInt()}g",
                    channel = pulse.carbs,
                    modifier = Modifier.weight(1f),
                )
                MacroAvgColumn(
                    label = "Fat",
                    value = "${avgFat.roundToInt()}g",
                    channel = pulse.fat,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MacroAvgColumn(
    label: String,
    value: String,
    channel: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        DataText(
            text = value,
            style = PlateTheme.dataType.dataSmall,
            color = channel,
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DayDetailCard(date: LocalDate, summary: DaySummary?, onOpen: () -> Unit) {
    val pulse = PlateTheme.pulse
    val hasData = (summary?.totals?.kcal ?: 0.0) > 0.0

    PanelCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column {
            Text(
                text = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                style = MaterialTheme.typography.labelMedium,
                color = pulse.carbs,
            )
            if (summary?.trained == true) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = pulse.proteinDim,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "Trained",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = pulse.protein,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!hasData) {
                Text(
                    text = "No food logged",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val totals = summary!!.totals
                Row(verticalAlignment = Alignment.Bottom) {
                    DataText(
                        text = totals.kcal.roundToInt().toString(),
                        style = PlateTheme.dataType.dataMedium,
                        color = pulse.calories,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "/ ${summary.targetKcal.roundToInt()} kcal",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        "Protein",
                        totals.proteinG.roundToInt().toString(),
                        modifier = Modifier.weight(1f),
                        unit = "g",
                        channel = pulse.protein,
                    )
                    StatTile(
                        "Carbs",
                        totals.carbsG.roundToInt().toString(),
                        modifier = Modifier.weight(1f),
                        unit = "g",
                        channel = pulse.carbs,
                    )
                    StatTile(
                        "Fat",
                        totals.fatG.roundToInt().toString(),
                        modifier = Modifier.weight(1f),
                        unit = "g",
                        channel = pulse.fat,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onOpen,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    text = if (hasData) "View & edit day" else "Log food for this day",
                    color = pulse.carbs,
                )
            }
        }
    }
}
