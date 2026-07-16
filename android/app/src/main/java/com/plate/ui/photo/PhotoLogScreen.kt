package com.plate.ui.photo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.ui.diary.DiaryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

private val MEALS = listOf("breakfast", "lunch", "dinner", "snack")
private val MEAL_LABELS = mapOf(
    "breakfast" to "Breakfast",
    "lunch" to "Lunch",
    "dinner" to "Dinner",
    "snack" to "Snacks",
)

/**
 * Photo logging (Phase 6, CLAUDE.md §6). The user picks or snaps a meal photo; the backend's vision
 * model returns an editable estimate of each food. The user confirms/edits each item before it's
 * logged — nothing is ever auto-committed.
 */
@Composable
fun PhotoLogScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    labelMode: Boolean = false,
    photoViewModel: PhotoLogViewModel = hiltViewModel(),
    diaryViewModel: DiaryViewModel = hiltViewModel(),
) {
    val state by photoViewModel.state.collectAsState()
    val date by diaryViewModel.date.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun analyzeUri(read: () -> Pair<ByteArray, String>?) {
        scope.launch {
            val data = withContext(Dispatchers.IO) { read() } ?: return@launch
            photoViewModel.analyze(data.first, data.second, label = labelMode)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) analyzeUri {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes() to (context.contentResolver.getType(uri) ?: "image/jpeg")
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        if (bitmap != null) analyzeUri { bitmap.toJpeg() to "image/jpeg" }
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) cameraLauncher.launch(null) }

    fun takePhoto() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) cameraLauncher.launch(null) else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            photoViewModel.clearError()
        }
    }

    PhotoLogContent(
        state = state,
        snackbar = snackbar,
        labelMode = labelMode,
        onBack = onBack,
        onDone = onDone,
        onPickGallery = {
            galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onTakePhoto = ::takePhoto,
        onRetake = photoViewModel::reset,
        onLog = { edited, meal ->
            photoViewModel.logDraft(edited, meal, date) { diaryViewModel.load() }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoLogContent(
    state: PhotoUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onPickGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onRetake: () -> Unit,
    onLog: (PhotoDraft, String) -> Unit,
    labelMode: Boolean = false,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (labelMode) "Scan nutrition label" else "Log from photo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.analyzing -> Analyzing(labelMode)
                !state.analyzed -> PickPrompt(
                    labelMode = labelMode,
                    onPickGallery = onPickGallery,
                    onTakePhoto = onTakePhoto,
                )
                else -> EstimateList(state = state, onRetake = onRetake, onDone = onDone, onLog = onLog)
            }
        }
    }
}

@Composable
private fun Analyzing(labelMode: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(
            if (labelMode) "Reading the nutrition label…" else "Estimating the food in your photo…",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PickPrompt(labelMode: Boolean, onPickGallery: () -> Unit, onTakePhoto: () -> Unit) {
    Text(
        if (labelMode) {
            "Snap or choose a photo of the Nutrition Facts label and Plate will read the macros for " +
                "you to confirm — more accurate than estimating from a meal photo."
        } else {
            "Snap or choose a photo of your meal and Plate will estimate the foods and macros for " +
                "you to confirm."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.AddAPhoto, contentDescription = null)
        Text(if (labelMode) "  Take a photo of the label" else "  Take a photo")
    }
    OutlinedButton(onClick = onPickGallery, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
        Text("  Choose from gallery")
    }
}

@Composable
private fun EstimateList(
    state: PhotoUiState,
    onRetake: () -> Unit,
    onDone: () -> Unit,
    onLog: (PhotoDraft, String) -> Unit,
) {
    // Estimates are never auto-logged — make that explicit (CLAUDE.md §3, §11).
    Text(
        "These are estimates — review and adjust each item before logging.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    if (state.drafts.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRetake, modifier = Modifier.fillMaxWidth()) {
            Text("Try another photo")
        }
        return
    }

    state.drafts.forEach { draft ->
        DraftCard(draft = draft, onLog = onLog)
    }
    OutlinedButton(onClick = onRetake, modifier = Modifier.fillMaxWidth()) {
        Text("Analyze another photo")
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftCard(draft: PhotoDraft, onLog: (PhotoDraft, String) -> Unit) {
    // Per-card edit state, seeded from the estimate. Keyed by id so it survives recomposition.
    var name by remember(draft.id) { mutableStateOf(draft.name) }
    var grams by remember(draft.id) { mutableStateOf(trimNum(draft.grams)) }
    var kcal by remember(draft.id) { mutableStateOf(trimNum(draft.kcal)) }
    var protein by remember(draft.id) { mutableStateOf(trimNum(draft.protein)) }
    var carbs by remember(draft.id) { mutableStateOf(trimNum(draft.carbs)) }
    var fat by remember(draft.id) { mutableStateOf(trimNum(draft.fat)) }
    var meal by remember(draft.id) { mutableStateOf("breakfast") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft.lowConfidence) {
                AssistChip(
                    onClick = {},
                    label = { Text("Low confidence — please double-check") },
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Food") },
                singleLine = true,
                enabled = !draft.logged,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Grams", grams, { grams = it }, !draft.logged)
                NumberField("kcal", kcal, { kcal = it }, !draft.logged)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Protein g", protein, { protein = it }, !draft.logged)
                NumberField("Carbs g", carbs, { carbs = it }, !draft.logged)
                NumberField("Fat g", fat, { fat = it }, !draft.logged)
            }
            Text("Meal", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MEALS.forEach { m ->
                    FilterChip(
                        selected = meal == m,
                        onClick = { meal = m },
                        enabled = !draft.logged,
                        label = { Text(MEAL_LABELS[m] ?: m) },
                    )
                }
            }
            if (draft.logged) {
                Text(
                    "Logged ✓",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Button(
                    onClick = {
                        onLog(
                            draft.copy(
                                name = name,
                                grams = grams.toDoubleOrNull() ?: 0.0,
                                kcal = kcal.toDoubleOrNull() ?: 0.0,
                                protein = protein.toDoubleOrNull() ?: 0.0,
                                carbs = carbs.toDoubleOrNull() ?: 0.0,
                                fat = fat.toDoubleOrNull() ?: 0.0,
                            ),
                            meal,
                        )
                    },
                    enabled = !draft.logging && name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (draft.logging) "Adding…" else "Add to diary")
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(0.3f),
    )
}

/** Drop a trailing ".0" so editable fields read "150" rather than "150.0". */
private fun trimNum(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else value.toString()

/** Compress a captured bitmap to JPEG bytes for upload. */
private fun Bitmap.toJpeg(quality: Int = 90): ByteArray =
    ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
