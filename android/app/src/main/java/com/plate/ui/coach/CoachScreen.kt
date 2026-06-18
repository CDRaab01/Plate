package com.plate.ui.coach

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.ui.theme.PlateTheme

@Composable
fun CoachScreen(
    viewModel: CoachViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    CoachContent(
        state = state,
        onSend = viewModel::sendMessage,
        onErrorShown = viewModel::clearError,
    )
}

/** Stateless chat body — rendered by [CoachScreen] in the app and by screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachContent(
    state: CoachUiState,
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
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (state.messages.isEmpty() && !state.sending) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState()
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(state.messages) { message -> MessageBubble(message) }
                    if (state.sending) {
                        item { TypingIndicator() }
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
private fun EmptyState() {
    com.plate.ui.components.EmptyState(
        icon = Icons.Default.AutoAwesome,
        title = "Meet your Plate Coach",
        subtitle = "Ask about recipes, food swaps, or how to hit your macros today.",
    )
}

@Composable
private fun MessageBubble(message: CoachMessage) {
    val isUser = message.isUser
    val pulse = PlateTheme.pulse
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlateTheme.spacing.md, vertical = PlateTheme.spacing.xs),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        val shape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isUser) 16.dp else 4.dp,
            bottomEnd = if (isUser) 4.dp else 16.dp,
        )
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(color = if (isUser) pulse.carbsDim else pulse.panel, shape = shape)
                .border(1.dp, if (isUser) pulse.carbs.copy(alpha = 0.25f) else pulse.hairline, shape)
                .padding(PlateTheme.spacing.md),
        ) {
            Text(
                text = message.content,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha0 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(0),
        ), label = "dot0",
    )
    val alpha1 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(150),
        ), label = "dot1",
    )
    val alpha2 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(300),
        ), label = "dot2",
    )

    val pulse = PlateTheme.pulse
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlateTheme.spacing.md, vertical = PlateTheme.spacing.xs),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = pulse.panel,
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomEnd = 16.dp, bottomStart = 4.dp,
                    ),
                )
                .padding(PlateTheme.spacing.md),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(alpha0, alpha1, alpha2).forEach { alpha ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color = pulse.carbs.copy(alpha = alpha), shape = CircleShape),
                    )
                }
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
    val pulse = PlateTheme.pulse
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Ask your coach…") },
            modifier = Modifier.weight(1f),
            maxLines = 4,
            enabled = enabled,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onSend,
            enabled = enabled && draft.isNotBlank(),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (draft.isNotBlank() && enabled) pulse.carbs
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
