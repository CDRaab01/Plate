package com.plate.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun CoachScreen(
    onBack: () -> Unit,
    viewModel: CoachViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    CoachContent(
        state = state,
        onBack = onBack,
        onSend = viewModel::sendMessage,
        onErrorShown = viewModel::clearError,
    )
}

/** Stateless chat body — rendered by [CoachScreen] in the app and by screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachContent(
    state: CoachUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onErrorShown: () -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Keep the newest message in view as the transcript grows.
    LaunchedEffect(state.messages.size, state.sending) {
        val count = state.messages.size + if (state.sending) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            onErrorShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Coach") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.messages.isEmpty() && !state.sending) {
                EmptyState(Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages) { message -> MessageBubble(message) }
                    if (state.sending) {
                        item { ThinkingBubble() }
                    }
                }
            }

            InputBar(
                draft = draft,
                onDraftChange = { draft = it },
                enabled = !state.sending,
                onSend = {
                    onSend(draft)
                    draft = ""
                },
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "Ask the coach about recipes, food swaps, or how to hit your macros for today.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MessageBubble(message: CoachMessage) {
    val alignment = if (message.isUser) Alignment.End else Alignment.Start
    val bubbleColor =
        if (message.isUser) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (message.isUser) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = bubbleColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.align(alignment).widthIn(max = 300.dp),
        ) {
            Text(
                message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.align(Alignment.Start),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Thinking…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Message the coach") },
            modifier = Modifier.weight(1f),
            maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onSend,
            enabled = enabled && draft.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}
