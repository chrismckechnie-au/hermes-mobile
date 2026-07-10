package au.com.chrismckechnie.hermesmobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF06100E)
private val InkRaised = Color(0xFF0A1513)
private val SurfaceOne = Color(0xFF0D1A17)
private val SurfaceTwo = Color(0xFF12211D)
private val Line = Color(0x1FFFFFFF)
private val LineStrong = Color(0x30FFFFFF)
private val Mint = Color(0xFF78F0B5)
private val MintSoft = Color(0xFFB7F8D8)
private val Muted = Color(0xFF789087)
private val SoftText = Color(0xFFC7D7D0)
private val Amber = Color(0xFFFFC56E)
private val Red = Color(0xFFFF817B)
private val Cyan = Color(0xFF72D8FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesMobileApp(state: HermesUiState, viewModel: HermesViewModel) {
    val colors = darkColorScheme(
        primary = Mint,
        onPrimary = Ink,
        background = Ink,
        surface = SurfaceOne,
        onSurface = Color(0xFFF0F8F4),
        error = Red,
    )

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF081412), Ink)))) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    CommandHeader(state = state, onChooseHost = viewModel::showHostPicker)
                    ConnectionNotice(
                        state = state,
                        onRetry = viewModel::retryConnection,
                        onManage = viewModel::showHostPicker,
                        onDismissError = viewModel::dismissError,
                    )
                    AnimatedContent(
                        targetState = state.screen,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(110)) },
                        label = "command-deck-screen",
                        modifier = Modifier.weight(1f),
                    ) { screen ->
                        when (screen) {
                            DeckScreen.Chat -> ChatScreen(state, viewModel)
                            DeckScreen.Sessions -> SessionsScreen(state, viewModel)
                            DeckScreen.Jobs -> JobsScreen(state)
                            DeckScreen.Host -> HostScreen(state, viewModel)
                        }
                    }
                    BottomDock(state.screen, viewModel::selectScreen)
                }

                if (state.showHostPicker) {
                    HostPickerSheet(
                        state = state,
                        onDismiss = viewModel::hideHostPicker,
                        onSelect = viewModel::selectHost,
                        onSave = viewModel::saveHost,
                        onDelete = viewModel::deleteHost,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandHeader(state: HermesUiState, onChooseHost: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Mint.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.AutoAwesome, null, tint = Mint, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("HERMES", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Text("Mobile command deck", color = Muted, fontSize = 9.sp)
            }
        }

        Row(
            modifier = Modifier.clip(CircleShape).background(Mint.copy(alpha = 0.055f)).clickable(onClick = onChooseHost).padding(start = 10.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusColor = when (state.connectionPhase) {
                HostConnectionPhase.Connected -> Mint
                HostConnectionPhase.Connecting -> Amber
                HostConnectionPhase.Failed -> Red
                HostConnectionPhase.NoHost -> Muted
            }
            Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(7.dp))
            Text(
                state.activeHost?.name ?: "Choose host",
                color = if (state.activeHost == null) SoftText else MintSoft,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(82.dp),
            )
            Icon(Icons.Outlined.ExpandMore, null, tint = Muted, modifier = Modifier.size(16.dp))
        }
    }
    HorizontalDivider(color = Line)
}

@Composable
private fun ConnectionNotice(
    state: HermesUiState,
    onRetry: () -> Unit,
    onManage: () -> Unit,
    onDismissError: () -> Unit,
) {
    AnimatedVisibility(visible = state.connectionPhase == HostConnectionPhase.Connecting) {
        Row(
            Modifier.fillMaxWidth().background(Amber.copy(alpha = 0.055f)).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(13.dp), color = Amber, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(8.dp))
            Text("Connecting to ${state.activeHost?.name ?: "Hermes"}…", color = Amber, fontSize = 9.sp)
        }
    }
    AnimatedVisibility(visible = state.connectionPhase == HostConnectionPhase.Failed || state.errorMessage != null) {
        Row(
            Modifier.fillMaxWidth().background(Red.copy(alpha = 0.075f)).padding(start = 14.dp, end = 5.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CloudOff, null, tint = Red, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                state.errorMessage ?: "Hermes host is unavailable.",
                color = Color(0xFFFFC8C5),
                fontSize = 9.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            if (state.connectionPhase == HostConnectionPhase.Failed) {
                TextButton(onClick = onRetry) { Text("Retry", color = Red, fontSize = 9.sp) }
                TextButton(onClick = onManage) { Text("Hosts", color = SoftText, fontSize = 9.sp) }
            } else {
                IconButton(onClick = onDismissError, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.Close, "Dismiss", tint = Muted, modifier = Modifier.size(17.dp)) }
            }
        }
    }
}

@Composable
private fun ChatScreen(state: HermesUiState, viewModel: HermesViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("ACTIVE THREAD", color = Muted, fontSize = 8.sp, letterSpacing = 1.25.sp)
                Text(
                    state.activeSession?.title?.takeIf { it.isNotBlank() } ?: "New conversation",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.45).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = viewModel::createSession,
                enabled = state.connectionPhase == HostConnectionPhase.Connected,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Mint.copy(alpha = 0.07f)),
            ) { Icon(Icons.Outlined.Add, "New session", tint = if (state.connectionPhase == HostConnectionPhase.Connected) Mint else Muted) }
        }

        if (state.messages.isEmpty()) {
            EmptyConversation(state, Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 15.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                items(state.messages, key = { it.id }) { item ->
                    when (item) {
                        is ChatUiItem.User -> UserBubble(item.text)
                        is ChatUiItem.Assistant -> AssistantMessage(item.text, item.streaming)
                        is ChatUiItem.Tool -> LiveToolCard(item)
                    }
                }
            }
        }
        Composer(state, viewModel)
    }
}

@Composable
private fun EmptyConversation(state: HermesUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 34.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(Mint.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) { Icon(if (state.activeHost == null) Icons.Outlined.Dns else Icons.Outlined.AutoAwesome, null, tint = Mint, modifier = Modifier.size(25.dp)) }
        Spacer(Modifier.height(17.dp))
        Text(
            when (state.connectionPhase) {
                HostConnectionPhase.Connected -> "Ready when you are"
                HostConnectionPhase.Connecting -> "Opening a secure channel"
                HostConnectionPhase.Failed -> "Host connection needs attention"
                HostConnectionPhase.NoHost -> "Connect your Hermes host"
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            when (state.connectionPhase) {
                HostConnectionPhase.Connected -> "Messages stream directly from ${state.activeHost?.name}. A new Hermes session is created on your first send."
                HostConnectionPhase.Connecting -> "Checking capabilities, sessions, and scheduled work."
                HostConnectionPhase.Failed -> "Retry the connection or choose another saved host."
                HostConnectionPhase.NoHost -> "Add the URL and API key for a desktop or server running the Hermes API server."
            },
            color = Muted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            color = Color(0xFFF0FCF6),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth(0.84f).clip(RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp)).background(Color(0xFF173129)).padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AssistantMessage(text: String, streaming: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(29.dp).clip(RoundedCornerShape(9.dp)).background(Mint.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Mint, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (text.isBlank() && streaming) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.4.dp, color = Mint)
                    Spacer(Modifier.width(8.dp))
                    Text("Hermes is working…", color = Muted, fontSize = 10.sp)
                }
            } else {
                Text(text, color = SoftText, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
                if (streaming) {
                    Text("STREAMING", color = Mint, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun LiveToolCard(item: ChatUiItem.Tool) {
    Card(
        modifier = Modifier.padding(start = 39.dp).fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1513)),
        border = BorderStroke(1.dp, if (item.failed) Red.copy(alpha = 0.28f) else Line),
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(33.dp).clip(RoundedCornerShape(10.dp)).background(Cyan.copy(alpha = 0.075f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Terminal, null, tint = if (item.failed) Red else Cyan, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(item.preview ?: if (item.running) "Running…" else "Completed", color = Muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (item.running) CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.5.dp, color = Cyan)
            else Text(if (item.failed) "FAILED" else "DONE", color = if (item.failed) Red else Mint, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Composer(state: HermesUiState, viewModel: HermesViewModel) {
    val enabled = state.connectionPhase == HostConnectionPhase.Connected && !state.isSending
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(SurfaceOne).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = state.composerText,
            onValueChange = viewModel::setComposerText,
            enabled = enabled,
            textStyle = TextStyle(color = Color(0xFFE8F1ED), fontSize = 12.sp, lineHeight = 17.sp),
            cursorBrush = SolidColor(Mint),
            modifier = Modifier.weight(1f),
            maxLines = 4,
            decorationBox = { inner ->
                Box(Modifier.padding(vertical = 6.dp)) {
                    if (state.composerText.isBlank()) Text(
                        if (state.activeHost == null) "Choose a host to begin" else if (state.connectionPhase != HostConnectionPhase.Connected) "Waiting for host…" else "Message Hermes…",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    inner()
                }
            },
        )
        Spacer(Modifier.width(7.dp))
        val canSend = enabled && state.composerText.isNotBlank()
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(if (canSend) Mint else Muted.copy(alpha = 0.12f)).clickable(enabled = canSend) {
                focusManager.clearFocus()
                viewModel.sendMessage()
            },
            contentAlignment = Alignment.Center,
        ) {
            if (state.isSending) CircularProgressIndicator(modifier = Modifier.size(17.dp), color = Mint, strokeWidth = 1.7.dp)
            else Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = if (canSend) Ink else Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SessionsScreen(state: HermesUiState, viewModel: HermesViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 15.dp)) {
        ScreenHeading("Sessions", "${state.sessions.size} on ${state.activeHost?.name ?: "no host"}", Icons.Outlined.Add, viewModel::createSession)
        if (state.sessions.isEmpty()) {
            EmptyListState(Icons.Outlined.History, "No sessions yet", "Start a message and Hermes will create one here.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)) {
                items(state.sessions, key = { it.id }) { session ->
                    SessionCard(session, selected = state.activeSessionId == session.id) { viewModel.selectSession(session.id) }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: HermesSession, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) Mint.copy(alpha = 0.075f) else Color(0xFF0A1513)),
        border = BorderStroke(1.dp, if (selected) Mint.copy(alpha = 0.28f) else Line),
        shape = RoundedCornerShape(15.dp),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) Mint else Muted.copy(alpha = 0.4f)))
                Spacer(Modifier.width(9.dp))
                Text(session.title?.takeIf { it.isNotBlank() } ?: "Untitled session", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${session.messageCount ?: 0} MSG", color = Muted, fontSize = 8.sp)
            }
            Text(session.preview?.takeIf { it.isNotBlank() } ?: "No preview available", color = Muted, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 17.dp))
            Text(listOfNotNull(session.source, session.model).joinToString(" · ").ifBlank { "Hermes session" }, color = Muted.copy(alpha = 0.72f), fontSize = 8.sp, modifier = Modifier.padding(top = 9.dp, start = 17.dp))
        }
    }
}

@Composable
private fun JobsScreen(state: HermesUiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 15.dp)) {
        ScreenHeading("Jobs", "Scheduled work from the selected host", Icons.Outlined.MoreHoriz, {})
        if (state.jobs.isEmpty()) {
            EmptyListState(Icons.Outlined.WorkOutline, "No scheduled jobs", "Jobs exposed by this Hermes host will appear here.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)) {
                items(state.jobs, key = { it.id }) { job -> JobCard(job) }
            }
        }
    }
}

@Composable
private fun JobCard(job: HermesJob) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1513)), border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(39.dp).clip(CircleShape).background(if (job.enabled) Mint else Muted.copy(alpha = 0.45f)))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(job.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(job.schedule, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                Text(job.deliver?.let { "Delivery · $it" } ?: "Local delivery", color = Muted.copy(alpha = 0.75f), fontSize = 8.sp, modifier = Modifier.padding(top = 7.dp))
            }
            Icon(if (job.enabled) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, tint = if (job.enabled) Mint else Muted)
        }
    }
}

@Composable
private fun HostScreen(state: HermesUiState, viewModel: HermesViewModel) {
    val host = state.activeHost
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp)) {
        ScreenHeading("Host", "Connection and capability status", Icons.Outlined.MoreHoriz, viewModel::showHostPicker)
        if (host == null) {
            EmptyListState(Icons.Outlined.Dns, "No host selected", "Choose a desktop or server running the Hermes API server.")
            return@Column
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = Mint.copy(alpha = 0.065f)),
            border = BorderStroke(1.dp, Mint.copy(alpha = 0.18f)),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(17.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Mint.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Dns, null, tint = Mint, modifier = Modifier.size(25.dp))
                    }
                    ConnectionBadge(state.connectionPhase)
                }
                Spacer(Modifier.height(15.dp))
                Text(host.name, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(host.baseUrl, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth().padding(top = 13.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric(state.sessions.size.toString(), "SESSIONS")
                    Metric(state.jobs.count { it.enabled }.toString(), "ACTIVE JOBS")
                    Metric(state.capabilities?.model ?: "—", "MODEL")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HostStatusRow(Icons.Outlined.Wifi, "Hermes API", state.capabilities?.platform ?: "Waiting for capabilities", if (state.connectionPhase == HostConnectionPhase.Connected) "READY" else "OFFLINE", if (state.connectionPhase == HostConnectionPhase.Connected) Mint else Red)
        HostStatusRow(Icons.Outlined.Shield, "Authentication", "Bearer key stored with Android Keystore encryption", "SECURE", Mint)
        HostStatusRow(Icons.Outlined.Language, "Transport", if (host.baseUrl.startsWith("https")) "HTTPS encrypted connection" else "Explicit private-network HTTP", if (host.baseUrl.startsWith("https")) "TLS" else "PRIVATE", if (host.baseUrl.startsWith("https")) Mint else Amber)
        Text("DISCOVERED CAPABILITIES", color = Muted, fontSize = 8.sp, letterSpacing = 1.1.sp, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            val features = state.capabilities?.features.orEmpty().ifEmpty { setOf("Waiting for host") }
            features.sorted().take(8).forEach { FeatureChip(it.replace('_', ' '), state.connectionPhase == HostConnectionPhase.Connected) }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Choose or manage hosts", Icons.Outlined.Dns, viewModel::showHostPicker)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ConnectionBadge(phase: HostConnectionPhase) {
    val (label, color) = when (phase) {
        HostConnectionPhase.Connected -> "ONLINE" to Mint
        HostConnectionPhase.Connecting -> "CONNECTING" to Amber
        HostConnectionPhase.Failed -> "OFFLINE" to Red
        HostConnectionPhase.NoHost -> "NO HOST" to Muted
    }
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = 0.09f)).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FeatureChip(label: String, enabled: Boolean) {
    Text(label.uppercase(), color = if (enabled) Mint else Muted, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(CircleShape).background(if (enabled) Mint.copy(alpha = 0.065f) else SurfaceOne).padding(horizontal = 10.dp, vertical = 7.dp))
}

@Composable
private fun ScreenHeading(title: String, subtitle: String, actionIcon: ImageVector, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.45).sp)
            Text(subtitle, color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onAction, modifier = Modifier.size(39.dp).clip(RoundedCornerShape(13.dp)).background(Mint.copy(alpha = 0.065f))) {
            Icon(actionIcon, null, tint = Mint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EmptyListState(icon: ImageVector, title: String, copy: String) {
    Column(Modifier.fillMaxWidth().padding(top = 72.dp, start = 30.dp, end = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Mint.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Mint, modifier = Modifier.size(23.dp)) }
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Text(copy, color = Muted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(modifier = Modifier.width(98.dp)) {
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = Muted, fontSize = 7.sp, letterSpacing = 0.65.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun HostStatusRow(icon: ImageVector, title: String, detail: String, state: String, stateColor: Color) {
    Card(modifier = Modifier.padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1513)), border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(35.dp).clip(RoundedCornerShape(11.dp)).background(Mint.copy(alpha = 0.055f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Mint, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            Text(state, color = stateColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().height(46.dp).clickable(onClick = onClick), color = Mint, contentColor = Ink, shape = RoundedCornerShape(14.dp)) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BottomDock(selected: DeckScreen, onSelect: (DeckScreen) -> Unit) {
    HorizontalDivider(color = Line)
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 5.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(
            Triple(DeckScreen.Chat, Icons.Outlined.ChatBubbleOutline, "Chat"),
            Triple(DeckScreen.Sessions, Icons.Outlined.History, "Sessions"),
            Triple(DeckScreen.Jobs, Icons.Outlined.WorkOutline, "Jobs"),
            Triple(DeckScreen.Host, Icons.Outlined.Dns, "Host"),
        ).forEach { (screen, icon, label) ->
            val active = screen == selected
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onSelect(screen) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, label, tint = if (active) Mint else Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(3.dp))
                Text(label, color = if (active) Mint else Muted, fontSize = 8.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostPickerSheet(
    state: HermesUiState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onSave: (String?, String, String, String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    var name by remember(state.showHostPicker) { mutableStateOf("") }
    var baseUrl by remember(state.showHostPicker) { mutableStateOf("https://") }
    var apiKey by remember(state.showHostPicker) { mutableStateOf("") }
    var allowHttp by remember(state.showHostPicker) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { if (state.hosts.isNotEmpty()) onDismiss() },
        sheetState = sheetState,
        containerColor = InkRaised,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        dragHandle = { Box(Modifier.padding(top = 9.dp, bottom = 4.dp).size(width = 38.dp, height = 4.dp).clip(CircleShape).background(LineStrong)) },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.hosts.isEmpty()) "Connect Hermes" else "Choose a host", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp)
                    Text("Switch between desktop and server instances without reconfiguring the app.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 5.dp))
                }
                if (state.hosts.isNotEmpty()) IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close", tint = Muted) }
            }

            if (state.hosts.isNotEmpty()) {
                Text("SAVED HOSTS", color = Muted, fontSize = 8.sp, letterSpacing = 1.15.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                state.hosts.forEach { host ->
                    SavedHostRow(host, selected = state.activeHostId == host.id, phase = if (state.activeHostId == host.id) state.connectionPhase else HostConnectionPhase.NoHost, onSelect = { onSelect(host.id) }, onDelete = { onDelete(host.id) })
                    Spacer(Modifier.height(7.dp))
                }
                HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 15.dp))
            } else {
                SecurityCallout()
            }

            Text("ADD A HOST", color = Muted, fontSize = 8.sp, letterSpacing = 1.15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 9.dp))
            HostTextField(name, { name = it }, "Host name", "Ubuntu Hermes", Icons.Outlined.Dns)
            Spacer(Modifier.height(9.dp))
            HostTextField(baseUrl, { baseUrl = it }, "Hermes server URL", "https://hermes.example.com", Icons.Outlined.Language)
            Spacer(Modifier.height(9.dp))
            HostTextField(apiKey, { apiKey = it }, "API key", "Required bearer token", Icons.Outlined.Key, password = true)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow private-network HTTP", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("Only for a trusted LAN or VPN. HTTPS is recommended.", color = Muted, fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Switch(
                    checked = allowHttp,
                    onCheckedChange = { allowHttp = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Mint, uncheckedThumbColor = Muted, uncheckedTrackColor = SurfaceTwo),
                )
            }
            PrimaryButton("Save and connect", Icons.Outlined.Wifi) { onSave(null, name, baseUrl, apiKey, allowHttp) }
            Text("Hermes Mobile probes /v1/capabilities before loading sessions. The API key is encrypted with Android Keystore and never shown again.", color = Muted, fontSize = 8.sp, lineHeight = 12.sp, modifier = Modifier.padding(top = 11.dp))
        }
    }
}

@Composable
private fun SecurityCallout() {
    Card(colors = CardDefaults.cardColors(containerColor = Mint.copy(alpha = 0.055f)), border = BorderStroke(1.dp, Mint.copy(alpha = 0.14f)), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(top = 17.dp, bottom = 16.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Lock, null, tint = Mint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Your host stays in control", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("The phone is only a client. Hermes, tools, memory, and credentials remain on the selected host.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SavedHostRow(
    host: HostProfile,
    selected: Boolean,
    phase: HostConnectionPhase,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) Mint.copy(alpha = 0.07f) else SurfaceOne),
        border = BorderStroke(1.dp, if (selected) Mint.copy(alpha = 0.25f) else Line),
        shape = RoundedCornerShape(15.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Mint.copy(alpha = 0.075f)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Dns, null, tint = Mint, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(host.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(host.baseUrl, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            if (selected) {
                val color = if (phase == HostConnectionPhase.Connected) Mint else if (phase == HostConnectionPhase.Failed) Red else Amber
                Icon(if (phase == HostConnectionPhase.Connected) Icons.Outlined.CheckCircle else Icons.Outlined.Refresh, null, tint = color, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(35.dp)) { Icon(Icons.Outlined.DeleteOutline, "Delete host", tint = Muted, modifier = Modifier.size(17.dp)) }
        }
    }
}

@Composable
private fun HostTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 10.sp) },
        placeholder = { Text(placeholder, color = Muted, fontSize = 10.sp) },
        leadingIcon = { Icon(icon, null, tint = Mint, modifier = Modifier.size(18.dp)) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(14.dp),
        textStyle = TextStyle(fontSize = 11.sp, color = Color(0xFFEAF4EF)),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Mint.copy(alpha = 0.6f),
            unfocusedBorderColor = LineStrong,
            focusedLabelColor = Mint,
            unfocusedLabelColor = Muted,
            cursorColor = Mint,
            focusedContainerColor = Color(0xFF091310),
            unfocusedContainerColor = Color(0xFF091310),
        ),
    )
}
