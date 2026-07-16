package com.plate.ui.voice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.ui.diary.DiaryViewModel
import com.plate.ui.photo.EstimateList
import com.plate.ui.photo.PhotoLogViewModel

/**
 * Voice logging (CLAUDE.md §6). The user taps the mic and speaks ("two eggs and a banana"). Speech→
 * text runs **on-device** via the system speech recognizer (offline preferred) — no audio leaves the
 * phone. The recognized *text* is sent to the server, which parses it, resolves foods, and returns
 * the same editable draft the photo path uses; the user confirms each item before it's logged
 * (nothing is auto-committed). The draft editor is shared with the photo/label screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLogScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    photoViewModel: PhotoLogViewModel = hiltViewModel(),
    diaryViewModel: DiaryViewModel = hiltViewModel(),
) {
    val state by photoViewModel.state.collectAsState()
    val date by diaryViewModel.date.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) photoViewModel.analyzeVoice(text)
        }
    }

    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // Prefer on-device recognition so no audio leaves the phone (CLAUDE.md §6).
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what you ate")
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { if (it is ActivityNotFoundException) photoViewModel.showError("Speech recognition isn't available on this device.") }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            photoViewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log by voice") },
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
                state.analyzing -> Analyzing()
                !state.analyzed -> MicPrompt(onListen = ::startListening)
                else -> EstimateList(
                    state = state,
                    onRetake = photoViewModel::reset,
                    onDone = onDone,
                    onLog = { edited, meal ->
                        photoViewModel.logDraft(edited, meal, date) { diaryViewModel.load() }
                    },
                    retakeLabel = "Say something else",
                    emptyRetakeLabel = "Try again",
                )
            }
        }
    }
}

@Composable
private fun Analyzing() {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text("Finding those foods…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MicPrompt(onListen: () -> Unit) {
    Text(
        "Tap the mic and say what you ate — for example, \"two eggs and a banana\". Plate will find " +
            "the foods for you to confirm. Speech is recognized on your device.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onListen, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Mic, contentDescription = null)
        Text("  Start speaking")
    }
}
