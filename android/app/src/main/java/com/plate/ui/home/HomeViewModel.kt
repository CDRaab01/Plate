package com.plate.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.ApiService
import com.plate.data.remote.DailyLog
import com.plate.data.remote.WeightTrendOut
import com.plate.data.repository.LogRepository
import com.plate.data.repository.MetricRepository
import com.plate.util.Greetings
import com.plate.util.UiState
import com.plate.util.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/** Everything the Home dashboard renders for today, assembled into one state object. */
data class HomeData(
    val day: DailyLog,
    val trend: WeightTrendOut?,
    /** Canonical-kg weigh-in series (oldest first) for the trend sparkline. */
    val weightSeriesKg: List<Float>,
)

/**
 * The Home dashboard: today's calorie ring + remaining macros, the weight trend + on-pace status,
 * a training-day badge (Spotter-awareness), and a weigh-in action. The cached weigh-in series is
 * observed from Room so the sparkline survives offline; the day + trend are fetched on load.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val logRepository: LogRepository,
    private val metricRepository: MetricRepository,
    private val appPreferences: com.plate.util.AppPreferences,
    private val api: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<HomeData>>(UiState.Loading)
    val state: StateFlow<UiState<HomeData>> = _state

    private val _greeting = MutableStateFlow(Greetings.forTime(LocalTime.now()))
    val greeting: StateFlow<String> = _greeting

    val mealNudge: StateFlow<String> = MutableStateFlow(Greetings.mealNudge(LocalTime.now()))

    /** The weigh-in entry unit (lb/kg) follows the user's preference. */
    val unitSystem: StateFlow<UnitSystem> = appPreferences.unitSystem
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnitSystem.IMPERIAL)

    /** Cached weigh-in series in kg (oldest first), for an offline-tolerant sparkline. */
    val weightSeriesKg: StateFlow<List<Float>> = metricRepository.metrics
        .map { rows -> rows.map { it.weightKg.toFloat() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        load()
        loadGreeting()
    }

    private fun loadGreeting() {
        viewModelScope.launch {
            val firstName = try {
                val me = api.me()
                // Reconcile the cached unit preference with the server's stored value.
                appPreferences.setUnitSystem(UnitSystem.fromWire(me.unitSystem))
                me.name.trim().substringBefore(' ').trim()
            } catch (_: Exception) {
                null
            }
            _greeting.value = Greetings.forTime(LocalTime.now(), firstName)
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = try {
                metricRepository.sync() // best-effort; cache backs the sparkline regardless
                val day = logRepository.getDay(LocalDate.now().toString())
                val trend = runCatching { metricRepository.getTrend() }.getOrNull()
                val series = weightSeriesKg.value
                UiState.Success(HomeData(day = day, trend = trend, weightSeriesKg = series))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load your day")
            }
        }
    }

    /** Log a weigh-in entered in the user's current unit, then refresh the dashboard. */
    fun logBodyweight(value: Double) {
        viewModelScope.launch {
            try {
                metricRepository.addMetric(
                    date = LocalDate.now().toString(),
                    weight = value,
                    unit = unitSystem.value,
                )
                load()
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Couldn't log your weight")
            }
        }
    }
}
