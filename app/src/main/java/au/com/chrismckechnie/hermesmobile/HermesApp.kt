package au.com.chrismckechnie.hermesmobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.History
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.ScrollText
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Wifi
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Zap
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val T: HermesPalette
    @Composable get() = LocalHermes.current

private fun phaseLabel(phase: HostConnectionPhase): String = when (phase) {
    HostConnectionPhase.Connected -> "ONLINE"
    HostConnectionPhase.Connecting -> "CONNECTING"
    HostConnectionPhase.Failed -> "OFFLINE"
    HostConnectionPhase.NoHost -> "NO HOST"
}

@Composable
private fun phaseColor(phase: HostConnectionPhase): Color = when (phase) {
    HostConnectionPhase.Connected -> T.Ok
    HostConnectionPhase.Connecting -> T.Warn
    HostConnectionPhase.Failed -> T.Error
    HostConnectionPhase.NoHost -> T.Muted
}

@Composable
private fun HermesAvatar(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.hermes_official),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(T.RadiusSmall)),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesMobileApp(
    state: HermesUiState,
    viewModel: HermesViewModel,
    permissionHealth: PermissionHealth = PermissionHealth(PermissionStatus.NotRequired, PermissionStatus.Denied),
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenOverlaySettings: () -> Unit = {},
) {
    var activitySheetOpen by remember { mutableStateOf(false) }
    val dark = when (state.themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val palette = if (dark) HermesDark else HermesLight
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !dark
            insets.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalHermes provides palette) {
        MaterialTheme(colorScheme = palette.ColorScheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = palette.Abyss) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().hermesBackdrop(palette))
                    val compact = LocalConfiguration.current.screenWidthDp <= 360
                    Column(
                        Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = 840.dp)
                            .align(Alignment.Center).statusBarsPadding(),
                    ) {
                        CommandHeader(
                            state = state,
                            compact = compact,
                            onChooseHost = viewModel::showHostPicker,
                            onOpenActivity = {
                                viewModel.refreshActivityHistory()
                                activitySheetOpen = true
                            },
                        )
                        ConnectionNotice(
                            state = state,
                            onRetry = viewModel::retryConnection,
                            onManage = viewModel::showHostPicker,
                            onDismissError = viewModel::dismissError,
                        )
                        ActiveWorkCentre(state, viewModel)
                        AnimatedContent(
                            targetState = state.screen,
                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(110)) },
                            label = "command-deck-screen",
                            modifier = Modifier.weight(1f),
                        ) { screen ->
                            when (screen) {
                                DeckScreen.Chat -> ChatScreen(state, viewModel)
                                DeckScreen.Sessions -> SessionsScreen(state, viewModel)
                                DeckScreen.Jobs -> JobsScreen(state, viewModel)
                                DeckScreen.Host -> HostScreen(state, viewModel)
                                DeckScreen.Settings -> SettingsScreen(
                                    state = state,
                                    viewModel = viewModel,
                                    permissionHealth = permissionHealth,
                                    onRequestNotificationPermission = onRequestNotificationPermission,
                                    onOpenNotificationSettings = onOpenNotificationSettings,
                                    onOpenOverlaySettings = onOpenOverlaySettings,
                                )
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
                            onEdit = viewModel::editHost,
                        )
                    }

                    if (activitySheetOpen) {
                        ActivityCenterSheet(
                            state = state,
                            onDismiss = { activitySheetOpen = false },
                            onOpen = { entry ->
                                viewModel.openSessionFromNotification(entry.hostId, entry.sessionId)
                                activitySheetOpen = false
                            },
                        )
                    }

                    state.confirmDeleteSessionId?.let { sessionId ->
                        DeleteSessionDialog(
                            session = state.sessions.firstOrNull { it.id == sessionId },
                            onConfirm = viewModel::confirmDeleteSession,
                            onDismiss = viewModel::dismissDeleteSession,
                        )
                    }
                    state.pendingPairing?.let { pairing ->
                        PairingConfirmationDialog(
                            pairing = pairing,
                            onConfirm = viewModel::confirmPairing,
                            onDismiss = viewModel::dismissPairing,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingConfirmationDialog(
    pairing: MobilePairingRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = T.SurfaceLow,
        title = { Text("Pair with this Hermes host?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pairing.baseUrl, style = T.MonoBody)
                Text(
                    if (pairing.baseUrl.startsWith("http://")) {
                        "This is a private-network HTTP host. Continue only on a trusted LAN or VPN."
                    } else {
                        "The one-time grant will be exchanged for a revocable device credential."
                    },
                    style = T.BodyMuted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CommandHeader(
    state: HermesUiState,
    compact: Boolean,
    onChooseHost: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 17.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HermesAvatar(Modifier.size(34.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("HERMES", style = T.Label.copy(letterSpacing = 1.8.sp))
                if (!compact) Text("Mobile command deck", style = T.Micro.copy(letterSpacing = 0.sp))
            }
        }

        val hostName = state.activeHost?.name ?: "Choose host"
        val statusText = phaseLabel(state.connectionPhase)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(
                    onClick = onOpenActivity,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Lucide.Bell, "Activity Center", tint = T.Cream, modifier = Modifier.size(20.dp))
                }
                if (state.unreadActivityCount > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(19.dp).clip(CircleShape).background(T.Error),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            state.unreadActivityCount.coerceAtMost(99).toString(),
                            style = T.Micro.copy(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        )
                    }
                }
            }
            Spacer(Modifier.width(3.dp))
            Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(T.Cream.copy(alpha = 0.055f))
                .clickable(onClick = onChooseHost)
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Hermes host $hostName, ${statusText.lowercase()}. Opens host picker."
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(phaseColor(state.connectionPhase)))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    hostName,
                    style = T.BodyMuted.copy(color = if (state.activeHost == null) T.TextSoft else T.CreamSoft, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp),
                )
                if (!compact) Text(statusText, style = T.Micro.copy(color = phaseColor(state.connectionPhase), letterSpacing = 0.8.sp))
            }
            Icon(Lucide.ChevronDown, null, tint = T.Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
    HorizontalDivider(color = T.Line)
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
            Modifier.fillMaxWidth().background(T.Warn.copy(alpha = 0.06f))
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = T.Warn, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(9.dp))
            Text("Connecting to ${state.activeHost?.name ?: "Hermes"}…", style = T.BodyMuted.copy(color = T.Warn))
        }
    }
    AnimatedVisibility(visible = state.connectionPhase == HostConnectionPhase.Failed || state.errorMessage != null) {
        Row(
            Modifier.fillMaxWidth().background(T.Error.copy(alpha = 0.08f))
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 14.dp, end = 5.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.CloudOff, null, tint = T.Error, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(9.dp))
            Text(
                state.errorMessage ?: "Hermes host is unavailable.",
                style = T.BodyMuted.copy(color = T.ErrorSoft),
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
            if (state.connectionPhase == HostConnectionPhase.Failed) {
                TextButton(onClick = onRetry) { Text("Retry", style = T.BodyMuted.copy(color = T.Error)) }
                TextButton(onClick = onManage) { Text("Hosts", style = T.BodyMuted.copy(color = T.TextSoft)) }
            } else {
                IconButton(onClick = onDismissError, modifier = Modifier.size(48.dp)) { Icon(Lucide.X, "Dismiss error", tint = T.Muted, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun ChatScreen(state: HermesUiState, viewModel: HermesViewModel) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activeSession = state.activeSession
    val canRenameSession = activeSession != null && state.capabilities?.supportsSessionEdit == true
    var renameOpen by remember(activeSession?.id) { mutableStateOf(false) }
    var renameText by remember(activeSession?.id) { mutableStateOf(activeSession?.title.orEmpty()) }
    var taskDrawerOpen by remember(activeSession?.id) { mutableStateOf(false) }
    var subagentDrawerOpen by remember(activeSession?.id) { mutableStateOf(false) }
    var workspaceDrawerOpen by remember(activeSession?.id) { mutableStateOf(false) }
    val displayedMessages = state.displayedMessages
    val durableTurn = state.activeSessionKey?.let(state.sessionActivity::get)?.let {
        it.activeTurn ?: it.latestTurn
    }
    val tasks = state.activeRun?.tasks ?: durableTurn?.tasks.orEmpty()
    val allSubagents = state.activeRun?.subagents?.values?.toList() ?: durableTurn?.subagents?.values?.toList().orEmpty()
    val workingSubagents = allSubagents.filter(HermesSubagent::isWorking)
    val workspaceUpdate = state.activeSessionKey?.let(state.workspaceUpdates::get)
        ?: state.activeRun?.workspaceUpdate
        ?: durableTurn?.workspaceUpdate
    val canShareTranscript = remember(displayedMessages) {
        displayedMessages.any { item ->
            item is ChatUiItem.User && item.text.isNotBlank() ||
                item is ChatUiItem.Assistant && item.text.isNotBlank()
        }
    }
    val timelineItems = remember(displayedMessages) { groupChatTimeline(displayedMessages) }
    val assistantAvatarIds = remember(displayedMessages) { firstAssistantIdsByTurn(displayedMessages) }
    val lastAssistantLength = (displayedMessages.lastOrNull { it is ChatUiItem.Assistant } as? ChatUiItem.Assistant)?.text?.length ?: 0
    val isNearLatest by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index
            layout.totalItemsCount == 0 || lastVisible == null || lastVisible >= layout.totalItemsCount - 2
        }
    }
    LaunchedEffect(displayedMessages.size, lastAssistantLength) {
        if (timelineItems.isNotEmpty() && isNearLatest && !listState.isScrollInProgress) {
            listState.scrollToItem(timelineItems.lastIndex, scrollOffset = 100_000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("ACTIVE THREAD", style = T.Micro)
                Text(
                    state.activeSession?.title?.takeIf { it.isNotBlank() } ?: "New conversation",
                    style = T.ScreenTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (canRenameSession) {
                        Modifier
                            .clip(RoundedCornerShape(T.RadiusSmall))
                            .clickable(onClickLabel = "Rename session") { renameOpen = true }
                            .semantics { contentDescription = "Rename session" }
                            .padding(vertical = 5.dp)
                    } else Modifier,
                )
            }
            if (canShareTranscript && activeSession != null) {
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        val title = activeSession.title
                        val messages = displayedMessages
                        scope.launch {
                            val transcript = withContext(Dispatchers.Default) {
                                formatSessionTranscript(title, messages)
                            }
                            shareTranscript(context, transcript)
                        }
                    },
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(T.RadiusCard)).background(T.Cream.copy(alpha = 0.07f)),
                ) { Icon(Lucide.Share2, "Share transcript", tint = T.Cream, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = viewModel::createSession,
                enabled = state.connectionPhase == HostConnectionPhase.Connected,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(T.RadiusCard)).background(T.Cream.copy(alpha = 0.07f)),
            ) { Icon(Lucide.Plus, "New session", tint = if (state.connectionPhase == HostConnectionPhase.Connected) T.Cream else T.Muted) }
        }

        if (state.transcriptResource is ResourceState.Loading && activeSession != null) {
            ResourceStatusPanel("Loading conversation…", modifier = Modifier.weight(1f))
        } else if (state.transcriptResource is ResourceState.Error && displayedMessages.isEmpty()) {
            ResourceStatusPanel(
                message = state.transcriptResource.message,
                action = "Retry",
                onAction = viewModel::retryTranscript,
                isError = true,
                showProgress = false,
                modifier = Modifier.weight(1f),
            )
        } else if (displayedMessages.isEmpty()) {
            EmptyConversation(state, onStarterPrompt = viewModel::setComposerText, modifier = Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    items(timelineItems, key = { it.id }) { timelineItem ->
                        when (timelineItem) {
                            is ChatTimelineItem.Message -> when (val item = timelineItem.item) {
                                is ChatUiItem.User -> UserBubble(item.text)
                                is ChatUiItem.Assistant -> AssistantMessage(
                                    item.text,
                                    item.streaming,
                                    item.safeStatus,
                                    item.safeStatusHistory,
                                    item.usage,
                                    showAvatar = item.id in assistantAvatarIds,
                                )
                                is ChatUiItem.Reasoning -> ReasoningCard(item)
                                is ChatUiItem.Tool -> ToolActivityGroup(listOf(item))
                                is ChatUiItem.Activity -> ActivityHistoryCard(item)
                                is ChatUiItem.Approval -> ApprovalCard(item, viewModel)
                            }
                            is ChatTimelineItem.ToolGroup -> ToolActivityGroup(timelineItem.tools)
                        }
                    }
                }
                if (!isNearLatest) {
                    TextButton(
                        onClick = {
                            if (timelineItems.isNotEmpty()) {
                                scope.launch {
                                    listState.animateScrollToItem(timelineItems.lastIndex, scrollOffset = 100_000)
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                            .clip(CircleShape).background(T.SurfaceOne),
                    ) {
                        Icon(Lucide.ChevronDown, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Latest", style = T.MicroBold)
                    }
                }
            }
        }
        Composer(
            state = state,
            viewModel = viewModel,
            tasks = tasks,
            subagents = allSubagents,
            workspaceUpdate = workspaceUpdate,
            onShowTasks = { taskDrawerOpen = true },
            onShowSubagents = { subagentDrawerOpen = true },
            onShowWorkspace = { workspaceDrawerOpen = true },
        )
    }

    if (renameOpen && activeSession != null) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            containerColor = T.SurfaceLow,
            title = { Text("Rename session", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = T.Body.copy(fontSize = 14.sp),
                    label = { Text("Session name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSession(activeSession.id, renameText)
                        renameOpen = false
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text("Save", style = T.BodyMuted.copy(color = T.Cream, fontSize = 13.sp)) }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Cancel", style = T.BodyMuted.copy(fontSize = 13.sp)) }
            },
        )
    }

    if (taskDrawerOpen && tasks.isNotEmpty()) {
        TaskPlanSheet(tasks = tasks, onDismiss = { taskDrawerOpen = false })
    }
    if (subagentDrawerOpen && allSubagents.isNotEmpty()) {
        SubagentSheet(subagents = allSubagents, onDismiss = { subagentDrawerOpen = false })
    }
    if (workspaceDrawerOpen && workspaceUpdate != null) {
        WorkspaceDiffSheet(update = workspaceUpdate, onDismiss = { workspaceDrawerOpen = false })
    }
}

@Composable
private fun LiveWorkPills(
    tasks: List<HermesTask>,
    subagents: List<HermesSubagent>,
    workspaceUpdate: HermesWorkspaceUpdate?,
    onShowTasks: () -> Unit,
    onShowSubagents: () -> Unit,
    onShowWorkspace: () -> Unit,
) {
    if (tasks.isEmpty() && subagents.isEmpty() && workspaceUpdate?.files.isNullOrEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (tasks.isNotEmpty()) {
            WorkPill(
                icon = Lucide.ScrollText,
                label = taskProgressLabel(tasks),
                contentDescription = "Show task plan, ${taskProgressLabel(tasks)}",
                onClick = onShowTasks,
            )
        }
        if (subagents.isNotEmpty()) {
            val label = "${subagents.size} subagent${if (subagents.size == 1) "" else "s"} working"
            WorkPill(
                icon = Lucide.MessageCircle,
                label = label,
                contentDescription = "Show working subagents, $label",
                onClick = onShowSubagents,
            )
        }
        workspaceUpdate?.files?.takeIf { it.isNotEmpty() }?.let { files ->
            val label = "${files.size} file${if (files.size == 1) "" else "s"} changed"
            WorkPill(
                icon = Lucide.ScrollText,
                label = label,
                contentDescription = "Show workspace diff, $label",
                onClick = onShowWorkspace,
            )
        }
    }
}

@Composable
private fun WorkPill(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(T.Cream.copy(alpha = 0.09f))
            .border(1.dp, T.Cream.copy(alpha = 0.18f), CircleShape)
            .clickable(onClickLabel = contentDescription, onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = T.Tool, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = T.MicroBold.copy(letterSpacing = 0.sp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceDiffSheet(update: HermesWorkspaceUpdate, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = T.SurfaceLow,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "workspace-heading") {
                Text("Workspace changes", style = T.ScreenTitle, modifier = Modifier.semantics { heading() })
                Text(
                    "${update.files.size} file${if (update.files.size == 1) "" else "s"} changed" +
                        if (update.truncated) " · partial result" else "",
                    style = T.BodyMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(update.files, key = HermesWorkspaceChange::path) { file ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(T.RadiusCard))
                        .background(T.Cream.copy(alpha = 0.045f))
                        .border(1.dp, T.Line, RoundedCornerShape(T.RadiusCard))
                        .padding(12.dp),
                ) {
                    Text(file.path, style = T.Label)
                    val totals = listOfNotNull(
                        file.additions?.let { "+$it" },
                        file.deletions?.let { "-$it" },
                    ).joinToString(" · ")
                    Text(
                        file.status.replace('_', ' ') + totals.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(),
                        style = T.Micro.copy(color = T.Muted),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    file.diff?.takeIf(String::isNotBlank)?.let { diff ->
                        Text(
                            diff,
                            style = T.BodyMuted.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskPlanSheet(tasks: List<HermesTask>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = T.SurfaceLow,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            item(key = "task-plan-heading") {
                Text("Task plan", style = T.ScreenTitle)
                Text(taskProgressLabel(tasks), style = T.BodyMuted, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            }
            items(tasks, key = HermesTask::id) { task ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    val tint = when (task.status) {
                        "completed" -> T.Ok
                        "cancelled" -> T.Muted
                        else -> T.AccentText
                    }
                    TaskStatusIcon(task.status, tint)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(task.content, style = T.Body)
                        Text(task.status.replace('_', ' ').uppercase(), style = T.Micro.copy(color = tint, letterSpacing = 0.5.sp), modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubagentSheet(subagents: List<HermesSubagent>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = T.SurfaceLow,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            item(key = "subagent-heading") {
                Text("Delegated work", style = T.ScreenTitle)
                Text("Live and completed subagents from this run", style = T.BodyMuted, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            }
            items(subagents.sortedBy(HermesSubagent::taskIndex), key = HermesSubagent::id) { subagent ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = T.Cream.copy(alpha = 0.055f)),
                    border = BorderStroke(1.dp, T.Line),
                    shape = RoundedCornerShape(T.RadiusCard),
                ) {
                    Column(Modifier.padding(start = 12.dp + (subagent.depth.coerceAtMost(4) * 10).dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                        Text(
                            if (subagent.taskCount > 1) "Subagent ${subagent.taskIndex + 1} of ${subagent.taskCount}" else "Subagent",
                            style = T.MicroBold.copy(color = T.Tool),
                        )
                        Text(subagent.goal ?: "Working on a delegated task", style = T.Label, modifier = Modifier.padding(top = 4.dp))
                        subagent.activity?.takeIf(String::isNotBlank)?.let { activity ->
                            Text(activity, style = T.BodyMuted, modifier = Modifier.padding(top = 5.dp))
                        }
                        Text(
                            buildString {
                                append(subagent.status.replace('_', ' ').uppercase())
                                if (subagent.toolCount > 0) append(" · ${subagent.toolCount} tools")
                            },
                            style = T.Micro.copy(color = T.Muted, letterSpacing = 0.5.sp),
                            modifier = Modifier.padding(top = 7.dp),
                        )
                        subagent.model?.takeIf(String::isNotBlank)?.let { model ->
                            Text(model, style = T.Micro.copy(color = T.Muted), modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityCenterSheet(
    state: HermesUiState,
    onDismiss: () -> Unit,
    onOpen: (ActivityEntry) -> Unit,
) {
    var showActive by remember { mutableStateOf(true) }
    val active = remember(
        state.activeRuns,
        state.activeHostSessions,
        state.sessions,
        state.hosts,
        state.activeHostId,
    ) { state.activeWorkItems() }
    val updates = state.activityEntries
        .filter { it.requiresAttention || it.isTerminal }
        .sortedByDescending(ActivityEntry::updatedAtMillis)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(horizontal = 17.dp)) {
            Text("ACTIVITY CENTER", style = T.Micro)
            Text("Hermes across every enabled host", style = T.ScreenTitle)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = showActive,
                    onClick = { showActive = true },
                    label = { Text("Active ${active.size}") },
                )
                FilterChip(
                    selected = !showActive,
                    onClick = { showActive = false },
                    label = { Text("Updates ${updates.size}") },
                )
            }
            Spacer(Modifier.height(8.dp))
            if (showActive) {
                if (active.isEmpty()) {
                    EmptyActivityCopy("No active Hermes work")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(active, key = { "${it.key.hostId}:${it.key.sessionId}:${it.ref?.runId.orEmpty()}" }) { item ->
                            ActivityCenterRow(
                                title = item.title,
                                host = item.hostName,
                                status = item.latestUpdate ?: attentionLabel(item.state),
                                unread = item.needsAttention,
                                onClick = {
                                    onOpen(
                                        ActivityEntry(
                                            item.key.hostId,
                                            item.key.sessionId,
                                            item.title,
                                            item.state,
                                            runId = item.ref?.runId,
                                            latestStatus = item.latestUpdate,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            } else {
                if (updates.isEmpty()) {
                    EmptyActivityCopy("No recent updates")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(updates, key = ActivityEntry::identity) { entry ->
                            ActivityCenterRow(
                                title = entry.title,
                                host = state.hosts.firstOrNull { it.id == entry.hostId }?.name ?: "Unknown host",
                                status = entry.latestStatus ?: attentionLabel(entry.state),
                                unread = !entry.read,
                                timestamp = activityTimeLabel(entry.updatedAtMillis),
                                onClick = { onOpen(entry) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActivityCenterRow(
    title: String,
    host: String,
    status: String,
    unread: Boolean,
    timestamp: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(T.RadiusCard)).clickable(onClick = onClick),
        color = if (unread) T.Cream.copy(alpha = 0.08f) else T.SurfaceLow,
        border = BorderStroke(1.dp, if (unread) T.Cream.copy(alpha = 0.22f) else T.Line),
        shape = RoundedCornerShape(T.RadiusCard),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape)
                    .background(if (unread) T.Warn else T.Muted),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = T.Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = T.BodyMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(host, style = T.Micro)
                    timestamp?.let { Text(it, style = T.Micro) }
                }
            }
        }
    }
}

@Composable
private fun EmptyActivityCopy(text: String) {
    Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
        Text(text, style = T.BodyMuted)
    }
}

private fun activityTimeLabel(value: Long): String {
    if (value <= 0L) return ""
    val elapsed = (System.currentTimeMillis() - value).coerceAtLeast(0L)
    return when {
        elapsed < 60_000L -> "now"
        elapsed < 3_600_000L -> "${elapsed / 60_000L}m"
        elapsed < 86_400_000L -> "${elapsed / 3_600_000L}h"
        else -> "${elapsed / 86_400_000L}d"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveWorkCentre(state: HermesUiState, viewModel: HermesViewModel) {
    val items = remember(
        state.activeRuns,
        state.activeHostSessions,
        state.sessions,
        state.hosts,
        state.activeHostId,
        state.activeSessionId,
    ) { state.activeWorkItems() }
    val bannerVisible = items.size > 1 || items.any { !it.isCurrentSession } ||
        (items.singleOrNull()?.isCurrentSession == true && state.screen != DeckScreen.Chat)
    var sheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(items.size) {
        if (items.isEmpty()) sheetOpen = false
    }

    AnimatedVisibility(visible = bannerVisible) {
        val summary = activeWorkSummary(items)
        val attention = items.any(ActiveWorkItem::needsAttention)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = T.ControlMin)
                .background(if (attention) T.Warn.copy(alpha = 0.09f) else T.AccentFill.copy(alpha = 0.07f))
                .clickable(
                    onClickLabel = "Show active work",
                    role = Role.Button,
                    onClick = { sheetOpen = true },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = "$summary. Show active work."
                    liveRegion = LiveRegionMode.Polite
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (attention) Lucide.ShieldCheck else Lucide.RefreshCw,
                null,
                tint = if (attention) T.Warn else T.AccentText,
                modifier = Modifier.size(T.IconSm),
            )
            Spacer(Modifier.width(9.dp))
            Text(summary, style = T.Status, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(Lucide.ChevronDown, null, tint = T.Muted, modifier = Modifier.size(T.IconSm))
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = T.SurfaceLow,
            scrimColor = T.Scrim,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 28.dp),
            ) {
                item(key = "active-work-heading") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Active work", style = T.SheetTitle, modifier = Modifier.semantics { heading() })
                            Text(activeWorkSummary(items), style = T.BodyMuted, modifier = Modifier.padding(top = 3.dp, bottom = 12.dp))
                        }
                        IconButton(onClick = { sheetOpen = false }, modifier = Modifier.size(T.ControlMin)) {
                            Icon(Lucide.X, "Close active work", tint = T.Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                items(
                    items = items,
                    key = { item -> "${item.key.hostId}:${item.key.sessionId}:${item.ref?.runId.orEmpty()}" },
                ) { item ->
                    ActiveWorkRow(
                        item = item,
                        onOpen = {
                            item.ref?.let(viewModel::returnToRunSession)
                                ?: viewModel.openSessionFromNotification(item.key.hostId, item.key.sessionId)
                            sheetOpen = false
                        },
                        onStop = item.ref?.let { ref -> { viewModel.stopRun(ref) } },
                        onRetry = item.ref?.let { ref -> { viewModel.retryRunReconciliation(ref) } },
                    )
                    HorizontalDivider(color = T.Line)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActiveWorkRow(
    item: ActiveWorkItem,
    onOpen: () -> Unit,
    onStop: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    val color = activeWorkStateColor(item.state)
    val itemDescription = listOfNotNull(
        "Active work item ${item.title}",
        item.hostName,
        activeWorkStateLabel(item.state),
        item.latestUpdate,
    ).joinToString(", ")
    var menuOpen by remember(item.key, item.ref?.runId) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(T.RadiusSmall))
                    .combinedClickable(
                        onClickLabel = "Open ${item.title}",
                        onLongClickLabel = "Show actions for ${item.title}",
                        onClick = onOpen,
                        onLongClick = { menuOpen = true },
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = itemDescription
                    }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = T.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${item.hostName} · ${activeWorkStateLabel(item.state)}",
                        style = T.BodyMuted.copy(color = color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    item.latestUpdate?.let { update ->
                        Text(update, style = T.BodyMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = T.SurfaceOne,
            ) {
                DropdownMenuItem(
                    text = { Text("Open session", style = T.BodyMuted.copy(color = T.TextSoft)) },
                    leadingIcon = { Icon(Lucide.MessageCircle, null, tint = T.AccentText, modifier = Modifier.size(17.dp)) },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    },
                )
                if (item.state in setOf("sync_required", "syncing") && onRetry != null) {
                    DropdownMenuItem(
                        text = { Text(if (item.state == "syncing") "Transcript syncing" else "Retry transcript sync", style = T.BodyMuted) },
                        leadingIcon = { Icon(Lucide.RefreshCw, null, tint = T.Warn, modifier = Modifier.size(17.dp)) },
                        enabled = item.state != "syncing",
                        onClick = {
                            menuOpen = false
                            onRetry()
                        },
                    )
                }
                if (onStop != null && item.state !in setOf("sync_required", "syncing")) {
                    DropdownMenuItem(
                        text = { Text(if (item.state == "stopping") "Stopping work" else "Stop work", style = T.BodyMuted.copy(color = T.Error)) },
                        leadingIcon = { Icon(Lucide.Square, null, tint = T.Error, modifier = Modifier.size(17.dp)) },
                        enabled = item.state != "stopping",
                        onClick = {
                            menuOpen = false
                            onStop()
                        },
                    )
                }
            }
        }
        when {
            item.state in setOf("sync_required", "syncing") && onRetry != null -> {
                TextButton(onClick = onRetry, enabled = item.state != "syncing", modifier = Modifier.heightIn(min = T.ControlMin)) {
                    Text(if (item.state == "syncing") "Syncing" else "Retry", style = T.Action.copy(color = T.Warn))
                }
            }
            onStop != null -> {
                IconButton(onClick = onStop, enabled = item.state != "stopping", modifier = Modifier.size(T.ControlMin)) {
                    if (item.state == "stopping") {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 1.5.dp, color = T.Warn)
                    } else {
                        Icon(Lucide.Square, "Stop ${item.title}", tint = T.Error, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

private fun activeWorkStateLabel(state: String): String = when (state) {
    "waiting_for_approval", "approval_required" -> "Approval required"
    "sync_required" -> "Transcript sync required"
    "syncing" -> "Syncing transcript"
    "stopping" -> "Stopping"
    "queued" -> "Queued"
    "stalled" -> "No recent activity"
    "unresponsive" -> "Unresponsive"
    "failed", "error" -> "Needs attention"
    else -> "Working"
}

@Composable
private fun activeWorkStateColor(state: String): Color = when (state) {
    "waiting_for_approval", "approval_required", "sync_required", "stalled", "unresponsive" -> T.Warn
    "failed", "error" -> T.Error
    "working", "running" -> T.Ok
    else -> T.AccentText
}

@Composable
private fun ModelChip(state: HermesUiState, viewModel: HermesViewModel) {
    var open by remember { mutableStateOf(false) }
    val supportsReasoning = state.capabilities?.supportsReasoningEffort == true
    val modelLabel = state.selectedModel
        ?: state.capabilities?.defaultModel?.takeIf(String::isNotBlank)
        ?: displaySessionModel(state.capabilities?.model, null)
        ?: state.models.firstOrNull()
    if (state.activeHost == null || (modelLabel == null && state.models.isEmpty() && !supportsReasoning)) return
    val chipLabel = state.selectedReasoningEffort
        ?.takeIf { supportsReasoning }
        ?.let { "${modelLabel ?: "Model"} · $it" }
        ?: modelLabel
        ?: "Model"

    ComposerControlPill(
        label = chipLabel,
        icon = Lucide.ChevronDown,
        contentDescription = "Model and reasoning settings",
        onClick = { open = true },
    )
    if (open) ModelSettingsSheet(state, viewModel, supportsReasoning) { open = false }
}

@Composable
private fun TaskStatusIcon(status: String, tint: Color) {
    val normalized = status.lowercase()
    AnimatedContent(
        targetState = normalized,
        transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
        label = "task-status-icon",
    ) { current ->
        when (current) {
            "completed" -> Icon(
                Lucide.CircleCheck,
                "Task completed",
                tint = T.Ok,
                modifier = Modifier.size(17.dp),
            )
            "cancelled" -> Icon(
                Lucide.X,
                "Task cancelled",
                tint = T.Muted,
                modifier = Modifier.size(17.dp),
            )
            "in_progress", "running", "working", "thinking" -> {
                val transition = rememberInfiniteTransition(label = "task-thinking")
                val rotation by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1_050, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "task-thinking-rotation",
                )
                Icon(
                    Lucide.RefreshCw,
                    "Task in progress",
                    tint = T.AccentText,
                    modifier = Modifier.size(17.dp).rotate(rotation),
                )
            }
            else -> Icon(
                Lucide.RefreshCw,
                "Task ${current.replace('_', ' ')}",
                tint = tint,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun PermissionChip(state: HermesUiState, viewModel: HermesViewModel) {
    if (state.capabilities?.supportsPermissionMode != true) return
    var open by remember { mutableStateOf(false) }
    ComposerControlPill(
        label = if (state.selectedPermissionMode == "full-access") "Full access · next run" else "Default permissions",
        icon = if (state.selectedPermissionMode == "full-access") Lucide.ShieldCheck else Lucide.Lock,
        contentDescription = "Permission settings",
        highlight = state.selectedPermissionMode == "full-access",
        onClick = { open = true },
    )
    if (open) PermissionSettingsSheet(state, viewModel) { open = false }
}

@Composable
private fun ComposerControlPill(
    label: String,
    icon: ImageVector,
    contentDescription: String,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(max = 210.dp)
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (highlight) T.Cream.copy(alpha = 0.14f) else T.Cream.copy(alpha = 0.07f))
            .clickable(onClickLabel = contentDescription, onClick = onClick)
            .padding(start = 11.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = T.MicroBold.copy(letterSpacing = 0.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(5.dp))
        Icon(icon, contentDescription, tint = if (highlight) T.Cream else T.Muted, modifier = Modifier.size(15.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSettingsSheet(
    state: HermesUiState,
    viewModel: HermesViewModel,
    supportsReasoning: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var modelQuery by remember(state.activeHostId) { mutableStateOf("") }
    val hostDefaultModel = state.capabilities?.defaultModel
        ?.takeIf { it.isNotBlank() }
        ?: displaySessionModel(state.capabilities?.model, null)
        ?: state.models.firstOrNull()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = T.SurfaceLow,
        scrimColor = T.Scrim,
        dragHandle = {
            Box(
                Modifier.padding(top = 9.dp, bottom = 4.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(T.LineStrong),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
            Text("Model settings", style = T.SheetTitle)
            Text(
                "Applies to new runs on ${state.activeHost?.name ?: "this host"}.",
                style = T.BodyMuted,
                modifier = Modifier.padding(top = 3.dp, bottom = 14.dp),
            )

            if (state.models.size > 8) {
                OutlinedTextField(
                    value = modelQuery,
                    onValueChange = { modelQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    singleLine = true,
                    label = { Text("Search models") },
                    leadingIcon = { Icon(Lucide.Search, null, modifier = Modifier.size(17.dp)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
            SelectorField(
                label = "MODEL",
                value = state.selectedModel ?: hostDefaultModel?.let { "$it (host default)" } ?: "—",
                options = buildList {
                    add(null to (hostDefaultModel?.let { "$it (host default)" } ?: "Host default"))
                    state.models.filter { it.contains(modelQuery, ignoreCase = true) }
                        .forEach { model -> add(model to model) }
                },
                selectedKey = state.selectedModel,
                onSelect = viewModel::selectModel,
            )

            if (supportsReasoning) {
                Spacer(Modifier.height(12.dp))
                SelectorField(
                    label = "REASONING EFFORT",
                    value = state.selectedReasoningEffort ?: "host default",
                    options = (listOf<String?>(null) + REASONING_EFFORTS).map { it to (it ?: "host default") },
                    selectedKey = state.selectedReasoningEffort,
                    onSelect = viewModel::selectReasoningEffort,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionSettingsSheet(
    state: HermesUiState,
    viewModel: HermesViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = T.SurfaceLow,
        scrimColor = T.Scrim,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
            Text("Permissions", style = T.SheetTitle)
            Text(
                "Choose how Hermes handles ordinary command approvals for runs in this session.",
                style = T.BodyMuted,
                modifier = Modifier.padding(top = 3.dp, bottom = 14.dp),
            )
            SelectorField(
                label = "RUN PERMISSIONS",
                value = if (state.selectedPermissionMode == "full-access") "Full access · next run" else "Default permissions",
                options = listOf(null to "Default permissions", "full-access" to "Full access · next run only"),
                selectedKey = state.selectedPermissionMode,
                onSelect = viewModel::selectPermissionMode,
            )
            if (state.selectedPermissionMode == "full-access") {
                Text(
                    "Full access is armed for the next run only. It is consumed when you send; host safety blocks still apply.",
                    style = T.BodyMuted.copy(color = T.Warn),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
    if (state.pendingFullAccessConfirmation != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelFullAccessConfirmation,
            containerColor = T.SurfaceLow,
            title = { Text("Use Full Access once?", style = T.SheetTitle) },
            text = {
                Text(
                    "The next run can act without ordinary approval prompts. This does not change the host policy and will not carry over to later runs.",
                    style = T.BodyMuted.copy(color = T.Warn),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmFullAccessForNextRun) {
                    Text("Confirm next run", style = T.Action.copy(color = T.Warn))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelFullAccessConfirmation) {
                    Text("Cancel", style = T.Action.copy(color = T.Muted))
                }
            },
        )
    }
}

@Composable
private fun SelectorField(
    label: String,
    value: String,
    options: List<Pair<String?, String>>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Text(label, style = T.Micro, modifier = Modifier.padding(bottom = 7.dp))
    Box {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(T.RadiusCard))
                .background(T.SurfaceOne)
                .border(BorderStroke(1.dp, T.LineStrong), RoundedCornerShape(T.RadiusCard))
                .clickable { expanded = true }
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = T.Label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Lucide.ChevronDown, null, tint = T.Muted, modifier = Modifier.size(17.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = T.SurfaceOne) {
            options.forEach { (key, display) ->
                val selected = key == selectedKey
                DropdownMenuItem(
                    text = {
                        Text(
                            display,
                            style = T.BodyMuted.copy(
                                color = if (selected) T.Cream else T.TextSoft,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                        )
                    },
                    trailingIcon = {
                        if (selected) Icon(Lucide.CircleCheck, null, tint = T.Cream, modifier = Modifier.size(15.dp))
                    },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

@Composable
private fun InlineResourceWarning(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(RoundedCornerShape(T.RadiusSmall))
            .background(T.Warn.copy(alpha = 0.08f))
            .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = T.BodyMuted.copy(color = T.Warn), modifier = Modifier.weight(1f), maxLines = 2)
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = T.ControlMin)) {
            Text("Retry", style = T.Action.copy(color = T.Warn))
        }
    }
}

@Composable
private fun ResourceStatusPanel(
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    isError: Boolean = false,
    showProgress: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showProgress) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 1.8.dp, color = T.Tool)
            Spacer(Modifier.height(12.dp))
        }
        Text(
            message,
            style = T.BodyMuted.copy(color = if (isError) T.Error else T.Muted),
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = T.ControlMin)) {
                Text(action, style = T.Action)
            }
        }
    }
}

@Composable
private fun EmptyConversation(
    state: HermesUiState,
    onStarterPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 34.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.activeHost == null) {
            Box(
                Modifier.size(54.dp).clip(RoundedCornerShape(T.RadiusSheet)).background(T.Cream.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Server, null, tint = T.Cream, modifier = Modifier.size(25.dp)) }
        } else {
            HermesAvatar(Modifier.size(54.dp))
        }
        Spacer(Modifier.height(17.dp))
        Text(
            when (state.connectionPhase) {
                HostConnectionPhase.Connected -> "Ready when you are"
                HostConnectionPhase.Connecting -> "Opening a secure channel"
                HostConnectionPhase.Failed -> "Host connection needs attention"
                HostConnectionPhase.NoHost -> "Connect your Hermes host"
            },
            style = T.CardTitle.copy(fontSize = 16.sp),
        )
        Text(
            when (state.connectionPhase) {
                HostConnectionPhase.Connected -> "Messages stream directly from ${state.activeHost?.name}. A new Hermes session is created on your first send."
                HostConnectionPhase.Connecting -> "Checking capabilities, sessions, and scheduled work."
                HostConnectionPhase.Failed -> "Retry the connection or choose another saved host."
                HostConnectionPhase.NoHost -> "Add the URL and API key for a desktop or server running the Hermes API server."
            },
            style = T.BodyMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (state.connectionPhase == HostConnectionPhase.Connected) {
            Spacer(Modifier.height(18.dp))
            Text("START WITH", style = T.Micro)
            Row(
                modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "Summarise this project",
                    "Review recent changes",
                    "Help me plan a task",
                ).forEach { prompt ->
                    WorkPill(
                        icon = Lucide.Zap,
                        label = prompt,
                        contentDescription = "Use starter prompt: $prompt",
                        onClick = { onStarterPrompt(prompt) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(text: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var actionsOpen by remember(text) { mutableStateOf(false) }
    // Right-aligned, hugging: short messages get a snug bubble, long ones
    // wrap within the row width minus the start inset.
    Box(Modifier.fillMaxWidth().padding(start = 48.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text,
                style = T.Body.copy(color = T.OnAccent),
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
                    .background(T.BubbleUser)
                    .combinedClickable(
                        onClick = {},
                        onLongClickLabel = "Show prompt actions",
                        onLongClick = { actionsOpen = true },
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        DropdownMenu(
            expanded = actionsOpen,
            onDismissRequest = { actionsOpen = false },
            containerColor = T.SurfaceOne,
        ) {
            DropdownMenuItem(
                text = { Text("Copy prompt", style = T.Action) },
                leadingIcon = { Icon(Lucide.Copy, null, tint = T.AccentText, modifier = Modifier.size(17.dp)) },
                onClick = {
                    clipboard.setText(AnnotatedString(text))
                    actionsOpen = false
                    Toast.makeText(context, "Prompt copied", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    text: String,
    streaming: Boolean,
    safeStatus: String?,
    safeStatusHistory: List<String>,
    usage: HermesRunUsage?,
    showAvatar: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (showAvatar) HermesAvatar(Modifier.size(29.dp)) else Spacer(Modifier.width(29.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (text.isBlank() && streaming) {
                LiveWorkingBubble(
                    status = safeStatus ?: "Hermes is working…",
                    updates = safeStatusHistory,
                )
            } else {
                MarkdownText(text, modifier = Modifier.padding(top = 2.dp))
                if (streaming) {
                    Text("STREAMING", style = T.MicroBold, modifier = Modifier.padding(top = 6.dp))
                }
                formatRunUsage(usage)?.let { summary ->
                    Text(summary, style = T.MicroBold, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

internal fun firstAssistantIdsByTurn(messages: List<ChatUiItem>): Set<String> = buildSet {
    var assistantSeenInTurn = false
    messages.forEach { item ->
        if (item is ChatUiItem.User) assistantSeenInTurn = false
        if (item is ChatUiItem.Assistant && !assistantSeenInTurn) {
            add(item.id)
            assistantSeenInTurn = true
        }
    }
}

/** Conversation export intentionally excludes tool previews and progress summaries. */
internal fun formatSessionTranscript(
    sessionTitle: String?,
    messages: List<ChatUiItem>,
): String = buildString {
    val title = sessionTitle?.trim().orEmpty().ifBlank { "Untitled session" }
    appendLine("Hermes Mobile transcript")
    appendLine("Session: $title")
    messages.forEach { message ->
        val (speaker, text) = when (message) {
            is ChatUiItem.User -> "You" to message.text
            is ChatUiItem.Assistant -> "Hermes" to message.text
            else -> return@forEach
        }
        if (text.isBlank()) return@forEach
        appendLine()
        appendLine("$speaker:")
        appendLine(text.trim())
    }
}

private fun shareTranscript(context: Context, transcript: String) {
    val shareIntent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, transcript)
    context.startActivity(Intent.createChooser(shareIntent, "Share Hermes transcript"))
}

@Composable
private fun ReasoningCard(item: ChatUiItem.Reasoning) {
    // Live activity is useful while a run is in progress, so expose it immediately.
    // The card stays collapsible once the user has reviewed it.
    var expanded by remember(item.id) { mutableStateOf(true) }
    val latest = item.updates.lastOrNull().orEmpty()
    val action = if (expanded) "Collapse Hermes activity" else "Expand Hermes activity"
    Card(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .semantics { contentDescription = action }
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = T.SurfaceLow.copy(alpha = 0.92f)),
        border = BorderStroke(1.dp, T.Cream.copy(alpha = 0.12f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(T.RadiusSmall)).background(T.Cream.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.ScrollText, null, tint = T.CreamSoft, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (expanded) "Hermes activity" else latest.ifBlank { "Hermes is working…" },
                style = if (expanded) T.Label else T.BodyMuted.copy(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(5.dp))
            Icon(
                Lucide.ChevronDown,
                action,
                tint = T.Muted,
                modifier = Modifier.size(15.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 38.dp, end = 9.dp, bottom = 7.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                item.updates.takeLast(12).forEach { update ->
                    Text(
                        update,
                        style = T.BodyMuted.copy(color = T.TextSoft, fontSize = 11.sp, lineHeight = 15.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityHistoryCard(item: ChatUiItem.Activity) {
    val latest = item.turns.lastOrNull()
    var expanded by remember(item.id) { mutableStateOf(latest?.terminal != true) }
    val activeCount = item.turns.count { !it.terminal }
    val label = when {
        activeCount > 0 -> "$activeCount active work trace${if (activeCount == 1) "" else "s"}"
        item.turns.size == 1 -> "Hermes work trace"
        else -> "${item.turns.size} Hermes work traces"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .semantics { contentDescription = if (expanded) "Collapse Hermes work trace" else "Expand Hermes work trace" }
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = T.SurfaceLow.copy(alpha = 0.92f)),
        border = BorderStroke(1.dp, if (activeCount > 0) T.Tool.copy(alpha = 0.24f) else T.Line),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.ScrollText, null, tint = T.Tool, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = T.Label)
                    Text(
                        latest?.latestStatus ?: "Host-provided Desktop activity",
                        style = T.Micro.copy(color = T.Muted, letterSpacing = 0.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (activeCount > 0) {
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.4.dp, color = T.Tool)
                }
                Icon(
                    Lucide.ChevronDown,
                    null,
                    tint = T.Muted,
                    modifier = Modifier.padding(start = 7.dp).size(15.dp).rotate(if (expanded) 180f else 0f),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item.turns.asReversed().forEach { turn -> ActivityTurnTrace(turn) }
                }
            }
        }
    }
}

@Composable
private fun ActivityTurnTrace(turn: SessionActivityTurn) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(T.RadiusSmall))
            .background(T.Cream.copy(alpha = 0.045f))
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (turn.terminal) "COMPLETED TRACE" else "LIVE TRACE",
                style = T.MicroBold.copy(color = if (turn.terminal) T.Muted else T.Tool, letterSpacing = 0.4.sp),
            )
            Spacer(Modifier.width(7.dp))
            Text(turn.latestStatus ?: "Hermes activity", style = T.Micro.copy(color = T.TextSoft, letterSpacing = 0.sp))
        }
        turn.reasoning.takeLast(5).forEach { update ->
            Text(update, style = T.BodyMuted.copy(fontSize = 11.sp, lineHeight = 15.sp))
        }
        turn.tools.takeLast(8).forEach { tool -> ToolActivityRow(tool) }
        val summary = buildList {
            if (turn.tasks.isNotEmpty()) add(taskProgressLabel(turn.tasks))
            if (turn.subagents.isNotEmpty()) add("${turn.subagents.values.count(HermesSubagent::isWorking)} subagents active")
            turn.workspaceUpdate?.files?.takeIf { it.isNotEmpty() }?.let { add("${it.size} files changed") }
        }.joinToString(" · ")
        if (summary.isNotBlank()) Text(summary, style = T.Micro.copy(color = T.Tool, letterSpacing = 0.sp))
    }
}

@Composable
private fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseMarkdownBlocks(text).forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    inlineAnnotated(block.text),
                    color = T.TextPrimary,
                    fontSize = if (block.level <= 2) 17.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                )
                is MarkdownBlock.Paragraph -> Text(
                    inlineAnnotated(block.text),
                    color = T.TextSoft,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
                is MarkdownBlock.Bullet -> Row {
                    Text("•", color = T.Cream, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(inlineAnnotated(block.text), color = T.TextSoft, fontSize = 15.sp, lineHeight = 22.sp)
                }
                is MarkdownBlock.Code -> Card(
                    colors = CardDefaults.cardColors(containerColor = T.SurfaceLow),
                    border = BorderStroke(1.dp, T.Line),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(block.language?.uppercase() ?: "CODE", style = T.Micro.copy(letterSpacing = 1.sp))
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(block.code)) },
                                modifier = Modifier.size(48.dp),
                            ) { Icon(Lucide.Copy, "Copy code", tint = T.Muted, modifier = Modifier.size(16.dp)) }
                        }
                        Text(
                            block.code,
                            style = T.MonoBody.copy(color = T.TextSoft, fontSize = 13.sp, lineHeight = 19.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun inlineAnnotated(text: String): AnnotatedString {
    val codeBg = T.Line
    val linkColor = T.Tool
    return buildAnnotatedString {
        parseInlineMarkdown(text).forEach { token ->
            val style = SpanStyle(
                fontWeight = if (token.bold) FontWeight.Bold else null,
                fontStyle = if (token.italic) FontStyle.Italic else null,
                fontFamily = if (token.code) T.Mono else null,
                background = if (token.code) codeBg else Color.Unspecified,
            )
            if (token.linkUrl != null) {
                withLink(
                    LinkAnnotation.Url(
                        token.linkUrl,
                        TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    )
                ) { append(token.text) }
            } else {
                withStyle(style) { append(token.text) }
            }
        }
    }
}

internal fun displayRunSessionName(run: ActiveRun, sessions: List<HermesSession>): String =
    sessions.firstOrNull { it.id == run.sessionId }?.title?.takeIf { it.isNotBlank() }
        ?: run.sessionTitle?.takeIf { it.isNotBlank() }
        ?: "Untitled session"

internal sealed interface ChatTimelineItem {
    val id: String

    data class Message(val item: ChatUiItem, override val id: String = item.id) : ChatTimelineItem

    data class ToolGroup(
        override val id: String,
        val tools: List<ChatUiItem.Tool>,
    ) : ChatTimelineItem
}

internal fun groupChatTimeline(items: List<ChatUiItem>): List<ChatTimelineItem> = buildList {
    val pendingTools = mutableListOf<ChatUiItem.Tool>()
    val usedKeys = mutableSetOf<String>()
    var toolGroupIndex: Int? = null
    fun uniqueKey(base: String): String {
        if (usedKeys.add(base)) return base
        var suffix = 2
        while (!usedKeys.add("$base#$suffix")) suffix += 1
        return "$base#$suffix"
    }
    fun resetToolGroup() {
        pendingTools.clear()
        toolGroupIndex = null
    }

    items.forEach { item ->
        when (item) {
            is ChatUiItem.User -> {
                resetToolGroup()
                add(ChatTimelineItem.Message(item, uniqueKey(item.id)))
            }
            is ChatUiItem.Tool -> {
                pendingTools += item
                val index = toolGroupIndex
                if (index == null) {
                    toolGroupIndex = size
                    add(ChatTimelineItem.ToolGroup(uniqueKey("tools:${item.id}"), pendingTools.toList()))
                } else {
                    this[index] = (this[index] as ChatTimelineItem.ToolGroup).copy(tools = pendingTools.toList())
                }
            }
            else -> add(ChatTimelineItem.Message(item, uniqueKey(item.id)))
        }
    }
}

@Composable
private fun ToolActivityGroup(tools: List<ChatUiItem.Tool>) {
    var expanded by remember(tools.first().id) { mutableStateOf(false) }
    val latest = tools.last()
    val action = if (expanded) "Collapse tool activity" else "Expand tool activity"
    val completed = tools.count { !it.running }
    Card(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .semantics { contentDescription = action }
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = T.SurfaceLow.copy(alpha = 0.92f)),
        border = BorderStroke(1.dp, if (latest.failed) T.Error.copy(alpha = 0.3f) else T.Tool.copy(alpha = 0.16f)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(22.dp).clip(RoundedCornerShape(T.RadiusSmall)).background(T.Tool.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Terminal, null, tint = if (latest.failed) T.Error else T.Tool, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hermes activity", style = T.Label)
                    Text(
                        toolActivitySummary(tools),
                        style = T.Micro.copy(color = T.Muted, letterSpacing = 0.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(5.dp))
                if (latest.running) CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.4.dp, color = T.Tool)
                else Text("$completed/${tools.size}", style = T.MicroBold.copy(color = if (latest.failed) T.Error else T.Tool))
                Icon(Lucide.ChevronDown, action, tint = T.Muted, modifier = Modifier.padding(start = 5.dp).size(15.dp).rotate(if (expanded) 180f else 0f))
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 38.dp, end = 9.dp, bottom = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tools.forEach { tool -> ToolActivityRow(tool) }
                }
            }
        }
    }
}

@Composable
private fun ToolActivityRow(item: ChatUiItem.Tool) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(compactToolSummary(item), style = T.MonoSmall.copy(color = T.TextSoft), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Text(
            if (item.running) "RUNNING" else if (item.failed) "FAILED" else "DONE",
            style = T.MicroBold.copy(color = if (item.failed) T.Error else T.Tool),
        )
    }
}

internal fun toolActivitySummary(tools: List<ChatUiItem.Tool>): String {
    val latest = tools.lastOrNull() ?: return "Working…"
    val latestTool = latest.name.replace('_', ' ').replace('-', ' ')
    return if (latest.running) "Working · $latestTool" else "${tools.size} tool step${if (tools.size == 1) "" else "s"} completed"
}

internal fun compactToolSummary(item: ChatUiItem.Tool): String {
    val detail = item.preview
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: if (item.running) "Running" else "Completed"
    return "${item.name} · $detail"
}

@Composable
private fun ApprovalCard(item: ChatUiItem.Approval, viewModel: HermesViewModel) {
    Card(
        modifier = Modifier.padding(start = 39.dp).fillMaxWidth(),
        shape = RoundedCornerShape(T.RadiusCard),
        colors = CardDefaults.cardColors(containerColor = T.Warn.copy(alpha = 0.06f)),
        border = BorderStroke(1.dp, T.Warn.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.ShieldCheck, null, tint = T.Warn, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
                Text(
                    if (item.submitting) "Sending approval…" else "Approval required",
                    style = T.CardTitle.copy(color = T.Warn),
                    modifier = Modifier.weight(1f),
                )
                if (item.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), color = T.Warn, strokeWidth = 1.5.dp)
                }
            }
            item.command?.let {
                Text(it, style = T.MonoBody.copy(color = T.TextSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 7.dp))
            }
            Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(
                    Triple("Deny", "deny", T.Error),
                    Triple("Allow once", "once", T.Cream),
                    Triple("Allow for run", "session", T.CreamSoft),
                ).forEach { (label, choice, color) ->
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable(
                            enabled = !item.submitting,
                            onClick = { viewModel.respondApproval(item.runRef, choice) },
                        ),
                        color = if (choice == "once") T.Cream else Color.Transparent,
                        contentColor = if (choice == "once") T.OnAccent else color,
                        shape = RoundedCornerShape(13.dp),
                        border = if (choice == "once") null else BorderStroke(1.dp, color.copy(alpha = 0.5f)),
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                style = T.MicroBold.copy(color = if (choice == "once") T.OnAccent else color, letterSpacing = 0.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    state: HermesUiState,
    viewModel: HermesViewModel,
    tasks: List<HermesTask>,
    subagents: List<HermesSubagent>,
    workspaceUpdate: HermesWorkspaceUpdate?,
    onShowTasks: () -> Unit,
    onShowSubagents: () -> Unit,
    onShowWorkspace: () -> Unit,
) {
    val suggestions = state.slashSuggestions()
    val inputState = composerInputState(state)
    val onTextChanged = remember(viewModel) { viewModel::setComposerText }
    val onSend = remember(viewModel) { viewModel::sendMessage }
    val onStop = remember(viewModel) { viewModel::stopActiveRun }

    Column(Modifier.fillMaxWidth()) {
        LiveWorkPills(
            tasks = tasks,
            subagents = subagents.filter(HermesSubagent::isWorking),
            workspaceUpdate = workspaceUpdate,
            onShowTasks = onShowTasks,
            onShowSubagents = onShowSubagents,
            onShowWorkspace = onShowWorkspace,
        )
        state.queuedInterrupt?.takeIf(QueuedInterrupt::requiresAcknowledgement)?.let { queued ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 11.dp)
                    .clip(RoundedCornerShape(T.RadiusCard))
                    .background(T.Warn.copy(alpha = 0.07f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("Recovered follow-up", style = T.MicroBold.copy(color = T.Warn))
                Text(
                    queued.text,
                    style = T.BodyMuted.copy(color = T.TextSoft),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { viewModel.acknowledgeQueuedInterrupt(useDraft = false) }) {
                        Text("Discard", style = T.MicroBold.copy(color = T.Error))
                    }
                    TextButton(onClick = { viewModel.acknowledgeQueuedInterrupt(useDraft = true) }) {
                        Text("Use draft", style = T.MicroBold)
                    }
                }
            }
        }

        state.unknownOutcome?.let { pending ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 11.dp)
                    .clip(RoundedCornerShape(T.RadiusCard))
                    .background(T.Warn.copy(alpha = 0.07f))
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!pending.evidence && !pending.timedOut) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = T.Warn, strokeWidth = 1.4.dp)
                } else {
                    Icon(Lucide.ShieldCheck, null, tint = T.Warn, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        pending.evidence -> "The host received your message and replied."
                        pending.timedOut -> "Send outcome unknown — no evidence the host received it."
                        else -> "Send outcome unknown — checking the host…"
                    },
                    style = T.BodyMuted.copy(color = T.Warn),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::acknowledgeUnknownOutcome) {
                    Text(if (pending.evidence) "Resume" else "Send anyway", style = T.MicroBold)
                }
            }
        }

        if (suggestions.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 11.dp)
                    .clip(RoundedCornerShape(T.RadiusCard))
                    .background(T.SurfaceLow)
                    .padding(vertical = 4.dp),
            ) {
                suggestions.forEach { suggestion ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.applySuggestion(suggestion) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (suggestion.kind == SlashKind.HostCommand) {
                            Icon(Lucide.Zap, null, tint = T.Cream, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(7.dp))
                        }
                        Text("/${suggestion.name}", style = T.MonoBody.copy(color = T.Cream, fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.width(10.dp))
                        Text(suggestion.description, style = T.BodyMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            when (suggestion.kind) {
                                SlashKind.HostCommand -> "COMMAND"
                                SlashKind.Skill -> "SKILL"
                                SlashKind.Command -> "LOCAL"
                            },
                            style = T.MicroBold,
                        )
                    }
                }
            }
        }

        if (state.activeHost != null) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 11.dp, end = 11.dp, top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelChip(state, viewModel)
                PermissionChip(state, viewModel)
            }
        }

        ComposerInput(inputState, onTextChanged, onSend, onStop)
    }
    state.pendingFollowUpChoice?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPendingFollowUp,
            containerColor = T.SurfaceLow,
            title = { Text("Send follow-up", style = T.SheetTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pending.text, style = T.Body, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Queue waits for the current run. Interrupt stops it before sending.",
                        style = T.BodyMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::queuePendingFollowUp) {
                    Text("Queue after run", style = T.Action)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::cancelPendingFollowUp) {
                        Text("Cancel", style = T.Action.copy(color = T.Muted))
                    }
                    TextButton(onClick = viewModel::interruptPendingFollowUp) {
                        Text("Interrupt now", style = T.Action.copy(color = T.Warn))
                    }
                }
            },
        )
    }
}

@Composable
private fun ComposerInput(
    state: ComposerInputState,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val dictationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val dictated = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (dictated.isNotBlank()) {
            onTextChanged(listOf(state.composerText.trim(), dictated).filter(String::isNotBlank).joinToString(" "))
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(T.RadiusSheet)).background(T.SurfaceOne)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = state.composerText,
            onValueChange = onTextChanged,
            enabled = state.enabled,
            textStyle = T.Body.copy(color = T.TextSoft),
            cursorBrush = SolidColor(T.Cream),
            modifier = Modifier.weight(1f),
            maxLines = 4,
            decorationBox = { inner ->
                Box(Modifier.padding(vertical = 6.dp)) {
                    if (state.composerText.isBlank()) Text(
                        when {
                            !state.hasActiveHost -> "Choose a host to begin"
                            state.connectionPhase != HostConnectionPhase.Connected -> "Waiting for host…"
                            state.activeRun != null -> "Type a follow-up…"
                            else -> "Message Hermes, or / for commands"
                        },
                        style = T.Body.copy(color = T.Muted),
                    )
                    inner()
                }
            },
        )
        Spacer(Modifier.width(7.dp))
        val activeRun = state.activeRun
        if (activeRun != null) {
            val canInterrupt = state.enabled && state.composerText.isNotBlank()
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(T.RadiusCard))
                    .background(
                        when {
                            activeRun.stopping -> T.Warn.copy(alpha = 0.12f)
                            canInterrupt -> T.Cream
                            else -> T.Error.copy(alpha = 0.12f)
                        },
                    )
                    .clickable(enabled = !activeRun.stopping || canInterrupt) {
                        if (canInterrupt) {
                            focusManager.clearFocus()
                            onSend()
                        } else {
                            onStop()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    activeRun.stopping -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 1.5.dp, color = T.Warn)
                    canInterrupt -> Icon(Lucide.Send, "Choose follow-up action", tint = T.OnAccent, modifier = Modifier.size(19.dp))
                    else -> Icon(Lucide.Square, "Stop run", tint = T.Error, modifier = Modifier.size(18.dp))
                }
            }
        } else if (state.isSending) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 1.5.dp, color = T.Cream)
            }
        } else {
            val dictationIntent = remember(context) {
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictate a message for Hermes")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
            }
            val canDictate = state.enabled && dictationIntent.resolveActivity(context.packageManager) != null
            IconButton(onClick = { dictationLauncher.launch(dictationIntent) }, enabled = canDictate) {
                Icon(Lucide.Mic, "Dictate message", tint = if (canDictate) T.Muted else T.Muted.copy(alpha = 0.35f), modifier = Modifier.size(19.dp))
            }
            val canSend = state.enabled && state.composerText.isNotBlank()
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(T.RadiusCard))
                    .background(if (canSend) T.Cream else T.Muted.copy(alpha = 0.12f))
                    .clickable(enabled = canSend) {
                        focusManager.clearFocus()
                        onSend()
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Send, "Send", tint = if (canSend) T.OnAccent else T.Muted, modifier = Modifier.size(19.dp)) }
        }
    }
    if (state.composerText.length >= 7_000) {
        Text(
            "${state.composerText.length.coerceAtMost(8_000)} / 8,000",
            style = T.Micro.copy(color = if (state.composerText.length >= 8_000) T.Warn else T.Muted),
            modifier = Modifier.fillMaxWidth().padding(end = 18.dp, bottom = 4.dp),
            textAlign = TextAlign.End,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsScreen(state: HermesUiState, viewModel: HermesViewModel) {
    var actionTarget by remember { mutableStateOf<HermesSession?>(null) }
    var renameTarget by remember { mutableStateOf<HermesSession?>(null) }
    var renameText by remember { mutableStateOf("") }
    var query by remember(state.activeHostId) { mutableStateOf("") }
    var sessionFilter by remember(state.activeHostId) { mutableStateOf(SessionFilter.All) }
    val orderedSessions = state.orderedSessions
    val activityBySession = remember(orderedSessions, state.activeRuns, state.activeHostSessions, state.activeHostId) {
        orderedSessions.associate { session -> session.id to state.activityFor(session)?.state }
    }
    val activeSessionIds = remember(activityBySession) {
        activityBySession.filterValues {
            it != null && it.lowercase() !in setOf("unresponsive", "stalled")
        }.keys
    }
    val approvalSessionIds = remember(activityBySession) {
        activityBySession.filterValues {
            it?.lowercase() in setOf("waiting_for_approval", "approval_required")
        }.keys
    }
    val stalledSessionIds = remember(activityBySession) {
        activityBySession.filterValues {
            it?.lowercase() in setOf("unresponsive", "stalled")
        }.keys
    }
    val defaultModel = state.capabilities?.defaultModel
    val filteredSessions = remember(
        orderedSessions,
        query,
        sessionFilter,
        activeSessionIds,
        approvalSessionIds,
        stalledSessionIds,
        defaultModel,
    ) {
        filterSessions(
            sessions = orderedSessions,
            query = query,
            filter = sessionFilter,
            activeSessionIds = activeSessionIds,
            approvalSessionIds = approvalSessionIds,
            stalledSessionIds = stalledSessionIds,
            defaultModel = defaultModel,
        )
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 15.dp)) {
        ScreenHeading("Sessions", "${state.sessions.size} on ${state.activeHost?.name ?: "no host"}", Lucide.Plus, "New session", viewModel::createSession)
        if (state.sessions.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search loaded sessions", style = T.BodyMuted) },
                leadingIcon = { Icon(Lucide.Search, null, tint = T.Muted, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (query.isBlank()) null else {
                    {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(48.dp)) {
                            Icon(Lucide.X, "Clear session search", tint = T.Muted, modifier = Modifier.size(17.dp))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
                textStyle = T.Body,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                SessionFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = sessionFilter == filter,
                        onClick = { sessionFilter = filter },
                        label = { Text(filter.label, style = T.MicroBold.copy(letterSpacing = 0.sp)) },
                    )
                }
            }
        }
        (state.sessionsResource as? ResourceState.Error)?.takeIf { state.sessions.isNotEmpty() }?.let {
            InlineResourceWarning(it.message, viewModel::refresh)
        }
        PullToRefreshBox(
            isRefreshing = state.sessionsRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            if (state.sessionsResource is ResourceState.Loading) {
                ResourceStatusPanel("Loading sessions…")
            } else if (state.sessionsResource is ResourceState.Error && state.sessions.isEmpty()) {
                ResourceStatusPanel(
                    message = state.sessionsResource.message,
                    action = "Retry",
                    onAction = viewModel::refresh,
                    isError = true,
                    showProgress = false,
                )
            } else if (state.sessions.isEmpty()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyListState(Lucide.History, "No sessions yet", "Start a message and Hermes will create one here. Pull down to refresh.")
                }
            } else if (filteredSessions.isEmpty()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyListState(Lucide.Search, "No matching sessions", "Searches titles, previews, source, and model across loaded sessions.")
                    if (state.sessionsHasMore) {
                        TextButton(
                            onClick = viewModel::loadMoreSessions,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text("Load older sessions to search more", style = T.BodyMuted.copy(color = T.Cream, fontSize = 13.sp))
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(filteredSessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            selected = state.activeSessionId == session.id,
                            activity = state.activityFor(session),
                            defaultModel = defaultModel,
                            onClick = { viewModel.selectSession(session.id) },
                            onLongClick = { actionTarget = session },
                        )
                    }
                    if (state.sessionsHasMore) {
                        item(key = "load-more") {
                            TextButton(
                                onClick = viewModel::loadMoreSessions,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text(
                                    if (query.isBlank()) "Load older sessions" else "Load older sessions to search more",
                                    style = T.BodyMuted.copy(color = T.Cream, fontSize = 13.sp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    actionTarget?.let { session ->
        val hostId = state.activeHost?.id
        val busy = hostId != null && state.isSessionBusy(hostId, session.id)
        val deleteBlocked = hostId != null && state.isSessionDeleteBlocked(hostId, session.id)
        val activity = state.activityFor(session)
        val canClearStale = activity?.isStalledActivity == true &&
            !activity.leaseId.isNullOrBlank() &&
            state.capabilities?.supportsActiveSessionCleanup == true
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            containerColor = T.SurfaceLow,
            dragHandle = null,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp).padding(bottom = 18.dp)) {
                Text(sessionDisplayTitle(session), style = T.SheetTitle)
                Text(
                    when {
                        busy && !deleteBlocked -> "This empty session can be deleted; other actions are unavailable."
                        busy -> "Session actions are unavailable while Hermes is working."
                        else -> "Choose a session action."
                    },
                    style = T.BodyMuted.copy(color = if (busy) T.Warn else T.Muted),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                SessionActionRow(
                    icon = Lucide.Pencil,
                    label = "Rename",
                    enabled = !busy && state.capabilities?.supportsSessionEdit == true,
                ) {
                    renameText = session.title.orEmpty()
                    renameTarget = session
                    actionTarget = null
                }
                SessionActionRow(
                    icon = Lucide.History,
                    label = "Fork session",
                    enabled = !busy && state.capabilities?.supportsSessionFork == true,
                ) {
                    viewModel.forkSession(session.id)
                    actionTarget = null
                }
                SessionActionRow(
                    icon = Lucide.Trash2,
                    label = "Delete",
                    enabled = !deleteBlocked && state.capabilities?.supportsSessionEdit == true,
                    destructive = true,
                ) {
                    viewModel.requestDeleteSession(session.id)
                    actionTarget = null
                }
                if (canClearStale) {
                    SessionActionRow(
                        icon = Lucide.RefreshCw,
                        label = "Clear stale activity",
                        enabled = true,
                    ) {
                        viewModel.clearStaleActivity(session.id)
                        actionTarget = null
                    }
                }
            }
        }
    }

    renameTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = T.SurfaceLow,
            title = { Text("Rename session", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = T.Body.copy(fontSize = 14.sp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSession(session.id, renameText)
                    renameTarget = null
                }) { Text("Save", style = T.BodyMuted.copy(color = T.Cream, fontSize = 13.sp)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel", style = T.BodyMuted.copy(fontSize = 13.sp)) }
            },
        )
    }
}

@Composable
private fun LiveWorkingBubble(status: String, updates: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    val action = if (expanded) "Collapse live Hermes status" else "Expand live Hermes status"
    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .semantics { contentDescription = action }
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = T.SurfaceLow),
        border = BorderStroke(1.dp, T.Cream.copy(alpha = 0.16f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.4.dp, color = T.Cream)
            Spacer(Modifier.width(8.dp))
            Text(
                status,
                style = T.BodyMuted.copy(color = T.TextSoft, fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Lucide.ChevronDown,
                action,
                tint = T.Muted,
                modifier = Modifier.size(15.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 32.dp, end = 10.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                updates.takeLast(8).forEach { update ->
                    Text(
                        update,
                        style = T.BodyMuted.copy(color = T.Muted, fontSize = 11.sp, lineHeight = 15.sp),
                    )
                }
                if (updates.isEmpty()) {
                    Text(status, style = T.BodyMuted.copy(color = T.Muted, fontSize = 11.sp, lineHeight = 15.sp))
                }
            }
        }
    }
}

@Composable
private fun SessionActionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> T.Muted.copy(alpha = 0.45f)
        destructive -> T.Error
        else -> T.Cream
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clip(RoundedCornerShape(T.RadiusCard))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(11.dp))
        Text(label, style = T.Label.copy(color = color))
    }
}

@Composable
private fun DeleteSessionDialog(
    session: HermesSession?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = session?.title?.takeIf { it.isNotBlank() } ?: "this session"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = T.SurfaceLow,
        title = { Text("Delete session?", style = T.CardTitle) },
        text = {
            Text(
                "Delete $name and its stored conversation from the Hermes host? This cannot be undone.",
                style = T.BodyMuted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", style = T.BodyMuted.copy(color = T.Error, fontSize = 13.sp))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = T.BodyMuted.copy(fontSize = 13.sp)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: HermesSession,
    selected: Boolean,
    activity: HermesActiveSession?,
    defaultModel: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val activityColor = activity?.let { sessionActivityColor(it.state) }
    val updatedAt = formatSessionUpdatedAt(session.lastActive)
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Session actions"),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> T.Cream.copy(alpha = 0.075f)
                activityColor != null -> activityColor.copy(alpha = 0.09f)
                else -> T.SurfaceLow
            },
        ),
        border = BorderStroke(1.dp, when {
            selected -> T.FocusRing
            activityColor != null -> activityColor.copy(alpha = 0.55f)
            else -> T.Line
        }),
        shape = RoundedCornerShape(T.RadiusCard),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(activityColor ?: if (selected) T.Cream else T.Muted.copy(alpha = 0.4f)))
                Spacer(Modifier.width(9.dp))
                Text(sessionDisplayTitle(session), style = T.Label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                activity?.let {
                    Text(sessionActivityLabel(it.state), style = T.MicroBold.copy(color = activityColor ?: T.Cream))
                    Spacer(Modifier.width(7.dp))
                }
                Text("${session.messageCount ?: 0} MSG", style = T.Micro)
            }
            Text(session.preview?.takeIf { it.isNotBlank() } ?: "No preview available", style = T.BodyMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 17.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp, start = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    listOfNotNull(sessionSourceLabel(session.source), displaySessionModel(session.model, defaultModel))
                        .filter(String::isNotBlank)
                        .joinToString(" · "),
                    style = T.Micro.copy(color = T.Muted.copy(alpha = 0.72f), letterSpacing = 0.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                updatedAt?.let { timestamp ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        timestamp,
                        style = T.Micro.copy(color = T.Muted.copy(alpha = 0.72f), letterSpacing = 0.sp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

private fun sessionActivityLabel(state: String): String = when (state.lowercase()) {
    "waiting_for_approval", "approval_required" -> "NEEDS APPROVAL"
    "unresponsive", "stalled" -> "NO RECENT ACTIVITY"
    "queued" -> "QUEUED"
    "stopping" -> "STOPPING"
    else -> "RUNNING"
}

@Composable
private fun sessionActivityColor(state: String): Color = when (state.lowercase()) {
    "waiting_for_approval", "approval_required", "stopping", "unresponsive", "stalled" -> T.Warn
    "failed", "error" -> T.Error
    else -> T.Ok
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobsScreen(state: HermesUiState, viewModel: HermesViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 15.dp)) {
        ScreenHeading("Jobs", "Scheduled work from the selected host", Lucide.RefreshCw, "Refresh jobs", viewModel::refresh)
        state.jobActionMessage?.let { message ->
            Text(
                message,
                style = T.Status.copy(color = if (message.startsWith("Could not")) T.Error else T.Ok),
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(bottom = 8.dp),
            )
        }
        (state.jobsResource as? ResourceState.Error)?.takeIf { state.jobs.isNotEmpty() }?.let {
            InlineResourceWarning(it.message, viewModel::refresh)
        }
        PullToRefreshBox(
            isRefreshing = state.jobsRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            if (state.jobsResource is ResourceState.Loading) {
                ResourceStatusPanel("Loading scheduled jobs…")
            } else if (state.jobsResource is ResourceState.Unsupported) {
                ResourceStatusPanel("Scheduled jobs are not supported by this host.", showProgress = false)
            } else if (state.jobsResource is ResourceState.Error && state.jobs.isEmpty()) {
                ResourceStatusPanel(
                    message = state.jobsResource.message,
                    action = "Retry",
                    onAction = viewModel::refresh,
                    isError = true,
                    showProgress = false,
                )
            } else if (state.jobs.isEmpty()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyListState(Lucide.CalendarClock, "No scheduled jobs", "Jobs exposed by this Hermes host will appear here. Pull down to refresh.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(state.jobs, key = { it.id }) { job ->
                        JobCard(
                            job = job,
                            runs = state.jobRuns[job.id],
                            toggling = "${state.activeHostId}:toggle:${job.id}" in state.jobActionsInFlight,
                            running = "${state.activeHostId}:run:${job.id}" in state.jobActionsInFlight,
                            onToggle = { viewModel.toggleJob(job) },
                            onRunNow = { viewModel.runJobNow(job.id) },
                            onLoadRuns = { viewModel.loadJobRuns(job.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JobCard(
    job: HermesJob,
    runs: ResourceState<List<HermesJobRun>>?,
    toggling: Boolean,
    running: Boolean,
    onToggle: () -> Unit,
    onRunNow: () -> Unit,
    onLoadRuns: () -> Unit,
) {
    var expanded by remember(job.id) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = T.SurfaceLow), border = BorderStroke(1.dp, T.Line), shape = RoundedCornerShape(T.RadiusCard)) {
        Column {
        Row(Modifier.fillMaxWidth().clickable {
            expanded = !expanded
            if (expanded && runs == null) onLoadRuns()
        }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(job.name, style = T.Label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (job.enabled) "ACTIVE" else "PAUSED",
                        style = T.MicroBold.copy(color = if (job.enabled) T.Cream else T.Muted),
                    )
                }
                Text(job.schedule, style = T.MonoBody, modifier = Modifier.padding(top = 4.dp))
                Text(job.deliver?.let { "Delivery · $it" } ?: "Local delivery", style = T.Micro.copy(color = T.Muted.copy(alpha = 0.75f), letterSpacing = 0.sp), modifier = Modifier.padding(top = 6.dp))
            }
            IconButton(onClick = onRunNow, enabled = !running, modifier = Modifier.size(48.dp)) {
                if (running) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 1.5.dp, color = T.Tool)
                else Icon(Lucide.Play, "Run job now", tint = T.Tool, modifier = Modifier.size(20.dp))
            }
            Switch(
                checked = job.enabled,
                enabled = !toggling,
                onCheckedChange = { onToggle() },
                modifier = Modifier.semantics { contentDescription = if (job.enabled) "Pause job ${job.name}" else "Resume job ${job.name}" },
                colors = SwitchDefaults.colors(checkedThumbColor = T.OnAccent, checkedTrackColor = T.Cream, uncheckedThumbColor = T.Muted, uncheckedTrackColor = T.SurfaceTwo),
            )
        }
        if (expanded) {
            HorizontalDivider(color = T.Line)
            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RECENT RUNS", style = T.Micro)
                when (runs) {
                    null, is ResourceState.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp, color = T.Tool)
                    is ResourceState.Error -> Text(runs.message, style = T.BodyMuted.copy(color = T.Error))
                    else -> {
                        val items = runs.itemsOrEmpty()
                        if (items.isEmpty()) Text("No recorded runs yet.", style = T.BodyMuted)
                        items.take(5).forEach { run ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(run.status.replace('_', ' '), style = T.Label)
                                Text("${run.messageCount} messages · ${run.toolCallCount} tools", style = T.Micro)
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun HostScreen(state: HermesUiState, viewModel: HermesViewModel) {
    val host = state.activeHost
    var libraryOpen by remember { mutableStateOf(false) }
    var confirmHostUpdate by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp)) {
        ScreenHeading("Host", "Connection and capability status", Lucide.Server, "Manage hosts", viewModel::showHostPicker)
        if (host == null) {
            EmptyListState(Lucide.Server, "No host selected", "Choose a desktop or server running the Hermes API server.")
            return@Column
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = T.Cream.copy(alpha = 0.065f)),
            border = BorderStroke(1.dp, T.Cream.copy(alpha = 0.18f)),
            shape = RoundedCornerShape(T.RadiusSheet),
        ) {
            Column(Modifier.padding(17.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(T.RadiusCard)).background(T.Cream.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Lucide.Server, null, tint = T.Cream, modifier = Modifier.size(25.dp))
                    }
                    ConnectionBadge(state.connectionPhase)
                }
                Spacer(Modifier.height(15.dp))
                Text(host.name, style = T.ScreenTitle)
                Text(host.baseUrl, style = T.MonoBody, modifier = Modifier.padding(top = 5.dp))
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = T.Line)
                Row(Modifier.fillMaxWidth().padding(top = 13.dp)) {
                    Metric(state.sessions.size.toString() + if (state.sessionsHasMore) "+" else "", "SESSIONS", Modifier.weight(1f))
                    Metric(state.jobs.count { it.enabled }.toString(), "ACTIVE JOBS", Modifier.weight(1f))
                    Metric(state.capabilities?.model ?: "—", "MODEL", Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HostStatusRow(Lucide.Wifi, "Hermes API", state.capabilities?.platform ?: "Waiting for capabilities", if (state.connectionPhase == HostConnectionPhase.Connected) "READY" else "OFFLINE", if (state.connectionPhase == HostConnectionPhase.Connected) T.Cream else T.Error)
        HostStatusRow(
            Lucide.Server,
            "Hermes Agent",
            state.capabilities?.version?.let { "Version $it" } ?: "Version unavailable on this host",
            if (state.capabilities?.version != null) "VERSION" else "UNKNOWN",
            if (state.capabilities?.version != null) T.Cream else T.Muted,
        )
        HostCompatibilityCard(state.capabilities)
        HostStatusRow(Lucide.ShieldCheck, "Authentication", "Bearer key stored with Android Keystore encryption", "SECURE", T.Cream)
        HostStatusRow(Lucide.Globe, "Transport", if (host.baseUrl.startsWith("https")) "HTTPS encrypted connection" else "Explicit private-network HTTP", if (host.baseUrl.startsWith("https")) "TLS" else "PRIVATE", if (host.baseUrl.startsWith("https")) T.Cream else T.Warn)
        if (state.capabilities?.supportsHostUpdate == true) {
            HostUpdateCard(state, onCheck = viewModel::checkHostUpdate, onUpdate = { confirmHostUpdate = true })
        }
        Text("SKILLS & TOOLS", style = T.Micro, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        Surface(
            color = T.SurfaceLow,
            border = BorderStroke(1.dp, T.Line),
            shape = RoundedCornerShape(T.RadiusCard),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable { libraryOpen = true }.padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Terminal, null, tint = T.Tool, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Browse skills & tools", style = T.Label)
                    Text(
                        "${state.skills.size} skills · ${state.toolsets.count { it.enabled }} enabled toolsets",
                        style = T.BodyMuted,
                    )
                }
                Icon(Lucide.ChevronDown, "Browse skills and tools", tint = T.Muted, modifier = Modifier.size(16.dp).rotate(-90f))
            }
        }
        Text("DISCOVERED CAPABILITIES", style = T.Micro, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            val features = state.capabilities?.features.orEmpty().ifEmpty { setOf("Waiting for host") }
            features.sorted().take(8).forEach { FeatureChip(it.replace('_', ' '), state.connectionPhase == HostConnectionPhase.Connected) }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Choose or manage hosts", Lucide.Server, onClick = viewModel::showHostPicker)
        Spacer(Modifier.height(16.dp))
    }
    if (libraryOpen) {
        SkillsAndToolsSheet(
            state = state,
            onDismiss = { libraryOpen = false },
            onRetry = viewModel::retryConnection,
            onUseSkill = { name ->
                viewModel.startSkill(name)
                libraryOpen = false
            },
        )
    }
    if (confirmHostUpdate) {
        AlertDialog(
            onDismissRequest = { confirmHostUpdate = false },
            containerColor = T.SurfaceLow,
            title = { Text("Update Hermes Agent?", fontSize = 16.sp) },
            text = {
                Text(
                    "The host will download and apply the update, then may briefly disconnect or restart. Active work must be stopped first.",
                    style = T.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateHost()
                    confirmHostUpdate = false
                }) { Text("Update host", style = T.BodyMuted.copy(color = T.Cream, fontSize = 13.sp)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmHostUpdate = false }) { Text("Cancel", style = T.BodyMuted.copy(fontSize = 13.sp)) }
            },
        )
    }
}

@Composable
private fun HostCompatibilityCard(capabilities: HermesCapabilities?) {
    val missingRunFeatures = remember(capabilities) {
        val required = setOf("run_submission", "run_events_sse", "run_stop", "approval_events", "run_approval_response")
        required - capabilities?.features.orEmpty()
    }
    val (detail, label, color) = when {
        capabilities == null -> Triple("Waiting for the host capability contract.", "CHECKING", T.Muted)
        missingRunFeatures.isNotEmpty() -> Triple(
            "This host is missing ${missingRunFeatures.joinToString { it.replace('_', ' ') }}. Chat controls stay disabled to avoid unmanageable runs.",
            "LIMITED",
            T.Warn,
        )
        capabilities.supportsReasoningEffort || capabilities.supportsHostUpdate -> Triple(
            "Official run control is ready. Advertised mobile extensions are enabled individually.",
            "EXTENDED",
            T.Ok,
        )
        else -> Triple(
            "Official run control is ready. Optional reasoning and host-update controls remain hidden unless the host advertises them.",
            "CORE READY",
            T.Ok,
        )
    }
    HostStatusRow(Lucide.CircleCheck, "Mobile compatibility", detail, label, color)
}

@Composable
private fun HostUpdateCard(
    state: HermesUiState,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
) {
    val update = state.hostUpdate
    Card(
        modifier = Modifier.padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = T.SurfaceLow),
        border = BorderStroke(1.dp, if (update?.updateAvailable == true) T.Warn.copy(alpha = 0.35f) else T.Line),
        shape = RoundedCornerShape(T.RadiusCard),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Host updates", style = T.Label)
                    Text(
                        when {
                            state.hostUpdateChecking -> "Checking for an update…"
                            update == null -> "Check whether this Hermes host has an update."
                            update.updateAvailable -> update.message ?: "An update is available for ${update.currentVersion}."
                            else -> update.message ?: "${update.currentVersion} is up to date."
                        },
                        style = T.BodyMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                TextButton(onClick = onCheck, enabled = !state.hostUpdateChecking && !state.hostUpdateStarting) {
                    Text("Check", style = T.MicroBold.copy(color = T.Cream))
                }
            }
            if (update?.updateAvailable == true) {
                HorizontalDivider(color = T.Line, modifier = Modifier.padding(top = 9.dp))
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (update.canApply) "Ready to update from this phone" else update.updateCommand ?: "Update this host from its install environment",
                        style = T.Micro.copy(color = if (update.canApply) T.Cream else T.Warn, letterSpacing = 0.sp),
                        modifier = Modifier.weight(1f),
                    )
                    if (update.canApply) {
                        TextButton(onClick = onUpdate, enabled = !state.hostUpdateStarting) {
                            Text(if (state.hostUpdateStarting) "Starting…" else "Update", style = T.MicroBold.copy(color = T.Cream))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillsAndToolsSheet(
    state: HermesUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onUseSkill: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember(state.activeHostId) { mutableStateOf("") }
    val matchingSkills = state.skills.filter {
        query.isBlank() || it.name.contains(query, true) || it.description.orEmpty().contains(query, true)
    }
    val matchingToolsets = state.toolsets.filter {
        query.isBlank() || it.label.contains(query, true) || it.tools.any { tool -> tool.contains(query, true) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = T.SurfaceLow,
        scrimColor = T.Scrim,
        dragHandle = { Box(Modifier.padding(top = 9.dp, bottom = 4.dp).size(width = 38.dp, height = 4.dp).clip(CircleShape).background(T.LineStrong)) },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Skills & tools", style = T.SheetTitle)
            Text("Available on ${state.activeHost?.name ?: "this host"}", style = T.BodyMuted)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search skills and tools") },
                leadingIcon = { Icon(Lucide.Search, null, modifier = Modifier.size(17.dp)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            Text("SKILLS", style = T.Micro, modifier = Modifier.padding(top = 8.dp))
            if (state.skillsResource is ResourceState.Loading) {
                Text("Loading skills…", style = T.BodyMuted)
            } else if (state.skillsResource is ResourceState.Error && state.skills.isEmpty()) {
                Text(state.skillsResource.message, style = T.BodyMuted.copy(color = T.Error))
                TextButton(onClick = onRetry) { Text("Retry", style = T.Action) }
            } else if (matchingSkills.isEmpty()) {
                Text("This host has not exposed any selectable skills.", style = T.BodyMuted)
            } else {
                matchingSkills.sortedBy(HermesSkill::name).forEach { skill ->
                    SkillLibraryRow(skill, onUseSkill)
                }
            }

            Text("TOOLS & PLUGINS", style = T.Micro, modifier = Modifier.padding(top = 10.dp))
            Text(
                "Host toolsets, including ones contributed by plugins, are listed here. Expand one to see the concrete tools Hermes can use.",
                style = T.BodyMuted,
            )
            if (state.toolsetsResource is ResourceState.Loading) {
                Text("Loading tools…", style = T.BodyMuted)
            } else if (state.toolsetsResource is ResourceState.Unsupported) {
                Text("This host does not support a toolset catalog.", style = T.BodyMuted)
            } else if (state.toolsetsResource is ResourceState.Error && state.toolsets.isEmpty()) {
                Text(state.toolsetsResource.message, style = T.BodyMuted.copy(color = T.Error))
                TextButton(onClick = onRetry) { Text("Retry", style = T.Action) }
            } else if (matchingToolsets.isEmpty()) {
                Text("This host does not expose a toolset catalog.", style = T.BodyMuted)
            } else {
                matchingToolsets.sortedBy(HermesToolset::label).forEach { toolset ->
                    ToolsetLibraryCard(toolset)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SkillLibraryRow(skill: HermesSkill, onUseSkill: (String) -> Unit) {
    Surface(color = T.SurfaceOne, border = BorderStroke(1.dp, T.Line), shape = RoundedCornerShape(T.RadiusCard)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(start = 13.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(skill.name, style = T.Label)
                Text(skill.description ?: "Host skill", style = T.BodyMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            TextButton(onClick = { onUseSkill(skill.name) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Use", style = T.MicroBold)
            }
        }
    }
}

@Composable
private fun ToolsetLibraryCard(toolset: HermesToolset) {
    var expanded by remember(toolset.name) { mutableStateOf(false) }
    val status = when {
        toolset.enabled -> "ENABLED"
        toolset.configured -> "CONFIGURED"
        else -> "OFF"
    }
    val statusColor = when {
        toolset.enabled -> T.Ok
        toolset.configured -> T.Warn
        else -> T.Muted
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = T.SurfaceOne),
        border = BorderStroke(1.dp, if (toolset.enabled) T.Tool.copy(alpha = 0.2f) else T.Line),
        shape = RoundedCornerShape(T.RadiusCard),
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(toolset.label, style = T.Label)
                    Text(toolset.description ?: "Host toolset", style = T.BodyMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
                Text(status, style = T.MicroBold.copy(color = statusColor))
                Icon(Lucide.ChevronDown, if (expanded) "Collapse tools" else "Expand tools", tint = T.Muted, modifier = Modifier.padding(start = 7.dp).size(15.dp).rotate(if (expanded) 180f else 0f))
            }
            Text("${toolset.tools.size} exposed tool${if (toolset.tools.size == 1) "" else "s"}", style = T.Micro.copy(letterSpacing = 0.sp), modifier = Modifier.padding(top = 7.dp))
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (toolset.tools.isEmpty()) Text("No tools reported by the host.", style = T.BodyMuted)
                    else toolset.tools.sorted().forEach { tool -> Text(tool, style = T.MonoSmall.copy(color = T.TextSoft)) }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: HermesUiState,
    viewModel: HermesViewModel,
    permissionHealth: PermissionHealth,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    var showLicenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val firebaseConfigured = remember(context) { FirebaseApp.getApps(context).isNotEmpty() }
    val previousExit = remember { AppDiagnosticsRegistry.recorder.latestExit() }
    val appVersion = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty().ifBlank { "Unknown" }
    }
    val overlayPermissionGranted = permissionHealth.overlay == PermissionStatus.Granted
    // The worker persists these safe registration states independently of the
    // ViewModel. Poll only while this Settings composable is visible so a
    // background retry is visible without making the app process long-lived.
    var registrationStatuses by remember(
        state.notificationHostIds,
        state.hosts.map(HostProfile::id),
    ) {
        mutableStateOf(MobileRegistration.statuses(context))
    }
    LaunchedEffect(state.notificationHostIds, state.hosts.map(HostProfile::id)) {
        while (true) {
            registrationStatuses = MobileRegistration.statuses(context)
            delay(if (registrationStatuses.any(MobileRegistrationStatus::pending)) 1_500L else 7_500L)
        }
    }
    val registrationByHost = remember(registrationStatuses) {
        registrationStatuses.associateBy(MobileRegistrationStatus::hostId)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp)) {
        ScreenHeading("Settings", "Appearance and app options")
        Text("APPEARANCE", style = T.Micro, modifier = Modifier.padding(bottom = 8.dp))
        Surface(color = T.SurfaceLow, border = BorderStroke(1.dp, T.Line), shape = RoundedCornerShape(T.RadiusCard)) {
            Column(Modifier.fillMaxWidth().padding(13.dp)) {
                Text("Theme", style = T.Label)
                Row(
                    Modifier.padding(top = 9.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = state.themeMode == mode
                        Text(
                            mode.name,
                            style = T.BodyMuted.copy(
                                color = if (selected) T.OnAccent else T.Muted,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (selected) T.Cream else T.SurfaceTwo)
                                .selectable(
                                    selected = selected,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                Text(
                    "System follows the Android dark-mode setting.",
                    style = T.BodyMuted,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
        }
        Text("HOST", style = T.Micro, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        Surface(color = T.SurfaceLow, border = BorderStroke(1.dp, T.Line), shape = RoundedCornerShape(T.RadiusCard)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = viewModel::showHostPicker).heightIn(min = 48.dp).padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Server, null, tint = T.Cream, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Manage hosts", style = T.Label)
                    Text(state.activeHost?.name ?: "No host selected", style = T.BodyMuted)
                }
                Icon(Lucide.ChevronDown, null, tint = T.Muted, modifier = Modifier.size(16.dp))
            }
        }
        Text("ANDROID PERMISSIONS", style = T.Micro, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        Surface(color = T.SurfaceLow, border = BorderStroke(1.dp, T.Line), shape = RoundedCornerShape(T.RadiusCard)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 5.dp)) {
                PermissionHealthRow(
                    title = "Notifications",
                    status = permissionHealth.notifications,
                    deniedCopy = "Required for approvals and completion alerts",
                    onRequest = onRequestNotificationPermission,
                    onOpenSettings = onOpenNotificationSettings,
                    showRequestAction = permissionHealth.canRequestNotificationPermission,
                )
                HorizontalDivider(color = T.Line)
                PermissionHealthRow(
                    title = "Display over other apps",
                    status = permissionHealth.overlay,
                    deniedCopy = "Required only for the floating active-work overlay",
                    onRequest = onOpenOverlaySettings,
                    onOpenSettings = onOpenOverlaySettings,
                    showSettingsAction = false,
                )
            }
        }
        Text("ACTIVE WORK MONITORING", style = T.Micro, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        Text(
            "Choose which hosts can show ongoing work in the app and floating overlay. This is independent of remote push.",
            style = T.BodyMuted,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Surface(color = T.SurfaceLow, border = BorderStroke(1.dp, T.Line), shape = RoundedCornerShape(T.RadiusCard)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 5.dp)) {
                state.hosts.forEach { host ->
                    val monitored = host.id in state.monitoredHostIds
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(host.name, style = T.Label)
                            Text(if (monitored) "Active work visible" else "Monitoring disabled", style = T.BodyMuted)
                        }
                        Switch(
                            checked = monitored,
                            onCheckedChange = { viewModel.setHostMonitoringEnabled(host.id, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = T.OnAccent, checkedTrackColor = T.Cream),
                        )
                    }
                }
                if (state.hosts.isEmpty()) Text("Add a host before enabling monitoring.", style = T.BodyMuted, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
        Text("NOTIFICATIONS", style = T.Micro, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        state.notificationTestMessage?.let { message ->
            Text(
                message,
                style = T.Status.copy(color = if (message.startsWith("Test failed")) T.Error else T.Ok),
                modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (!firebaseConfigured) {
            Text(
                "Remote push and Android Bubbles require a Firebase-configured APK. Ongoing status and the active-session overlay work for runs started here.",
                style = T.BodyMuted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Surface(color = T.SurfaceLow, border = BorderStroke(1.dp, T.Line), shape = RoundedCornerShape(T.RadiusCard)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 5.dp)) {
                state.hosts.forEach { host ->
                    val enabled = host.id in state.notificationHostIds
                    val registration = registrationByHost[host.id]
                    val registrationText = when {
                        !enabled && registration?.pending == true -> "Removing remote push…"
                        registration?.pending == true -> "Remote push: Pending"
                        !registration?.errorMessage.isNullOrBlank() -> "Remote push: Failed"
                        !enabled -> "Remote push disabled"
                        registration?.registered == true -> "Remote push: Registered"
                        else -> "Remote push: Preparing registration…"
                    }
                    val registrationColor = when {
                        registration?.pending == true -> T.Warn
                        !registration?.errorMessage.isNullOrBlank() -> T.Error
                        enabled && registration?.registered == true -> T.Ok
                        else -> T.Muted
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(host.name, style = T.Label)
                            Text(registrationText, style = T.BodyMuted.copy(color = registrationColor))
                            registration?.errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                                val unsupported = message.contains("not support", ignoreCase = true) ||
                                    message.contains("unavailable", ignoreCase = true)
                                Text(
                                    message,
                                    style = T.BodyMuted.copy(color = if (unsupported) T.Muted else T.Error),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { viewModel.setHostNotificationsEnabled(host.id, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = T.OnAccent, checkedTrackColor = T.Cream),
                        )
                    }
                    if (!registration?.errorMessage.isNullOrBlank()) {
                        TextButton(
                            onClick = { MobileRegistration.enqueueRetry(context) },
                            modifier = Modifier.padding(bottom = 5.dp),
                        ) {
                            Text("Retry remote push", style = T.BodyMuted.copy(color = T.Cream))
                        }
                    }
                    if (enabled && registration?.registered == true) {
                        TextButton(
                            enabled = host.id !in state.notificationTestHostIds,
                            onClick = { viewModel.testHostNotification(host.id) },
                            modifier = Modifier.padding(bottom = 5.dp),
                        ) {
                            if (host.id in state.notificationTestHostIds) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = T.Cream)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Send test notification", style = T.BodyMuted.copy(color = T.Cream))
                        }
                    }
                }
                if (state.hosts.isEmpty()) Text("Add a host before enabling notifications.", style = T.BodyMuted, modifier = Modifier.padding(vertical = 12.dp))
                HorizontalDivider(color = T.Line)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Floating active-session overlay", style = T.Label)
                        Text(
                            when {
                                !state.overlayEnabled -> "Shows opted-in hosts while sessions are active"
                                overlayPermissionGranted -> "Ready — appears while Hermes is actively working"
                                else -> "Android display-over-other-apps permission is required"
                            },
                            style = T.BodyMuted.copy(
                                color = if (state.overlayEnabled && !overlayPermissionGranted) T.Warn else T.Muted,
                            ),
                        )
                    }
                    Switch(
                        checked = state.overlayEnabled,
                        enabled = state.monitoredHostIds.isNotEmpty(),
                        onCheckedChange = viewModel::setOverlayEnabled,
                        colors = SwitchDefaults.colors(checkedThumbColor = T.OnAccent, checkedTrackColor = T.Cream),
                    )
                }
                if (state.overlayEnabled && !overlayPermissionGranted) {
                    TextButton(onClick = onOpenOverlaySettings, modifier = Modifier.heightIn(min = T.ControlMin)) {
                        Text("Grant overlay access", style = T.Action.copy(color = T.Warn))
                    }
                }
            }
        }
        Text("DIAGNOSTICS", style = T.Micro, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        Surface(color = T.SurfaceLow, border = BorderStroke(1.dp, T.Line), shape = RoundedCornerShape(T.RadiusCard)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 5.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Share crash diagnostics", style = T.Label)
                        Text(
                            when {
                                !firebaseConfigured -> "Unavailable in this APK because Firebase is not configured"
                                state.crashReportingEnabled -> "Enabled — Firebase collects crash and Android ANR reports"
                                else -> "Off by default — custom diagnostics exclude host and chat content"
                            },
                            style = T.BodyMuted,
                        )
                    }
                    Switch(
                        checked = state.crashReportingEnabled,
                        enabled = firebaseConfigured,
                        onCheckedChange = viewModel::setCrashReportingEnabled,
                        colors = SwitchDefaults.colors(checkedThumbColor = T.OnAccent, checkedTrackColor = T.Cream),
                    )
                }
                previousExit?.let { diagnostic ->
                    HorizontalDivider(color = T.Line)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Previous process exit", style = T.Label)
                            Text(
                                "${diagnostic.reason.replace('_', ' ')} · last phase ${diagnostic.lastPhase ?: "unknown"}",
                                style = T.BodyMuted,
                            )
                        }
                        TextButton(
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(
                                        formatProcessExitDiagnostic(
                                            diagnostic = diagnostic,
                                            appVersion = appVersion,
                                            sdkInt = Build.VERSION.SDK_INT,
                                            device = listOf(Build.MANUFACTURER, Build.MODEL)
                                                .filter(String::isNotBlank)
                                                .joinToString(" "),
                                        ),
                                    ),
                                )
                                Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Text("Copy", style = T.Action)
                        }
                    }
                }
            }
        }
        Text("ABOUT", style = T.Micro, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        Surface(color = T.SurfaceLow, border = BorderStroke(1.dp, T.Line), shape = RoundedCornerShape(T.RadiusCard)) {
            Column {
                AppVersionRow()
                HorizontalDivider(color = T.Line)
                LicensesRow { showLicenses = true }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (showLicenses) LicensesDialog { showLicenses = false }
}

@Composable
private fun PermissionHealthRow(
    title: String,
    status: PermissionStatus,
    deniedCopy: String,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    showRequestAction: Boolean = true,
    showSettingsAction: Boolean = true,
) {
    val (copy, color) = when (status) {
        PermissionStatus.NotRequired -> "Not required on this Android version" to T.Muted
        PermissionStatus.Granted -> "Granted" to T.Ok
        PermissionStatus.Denied -> deniedCopy to T.Warn
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = T.Label)
                Text(copy, style = T.BodyMuted.copy(color = color), modifier = Modifier.padding(top = 2.dp))
            }
            if (status == PermissionStatus.Denied && showRequestAction) {
                TextButton(onClick = onRequest, modifier = Modifier.heightIn(min = T.ControlMin)) {
                    Text("Grant", style = T.Action)
                }
            }
        }
        if (status == PermissionStatus.Denied && showSettingsAction) {
            TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = T.ControlMin)) {
                Text("Open Android settings", style = T.Action)
            }
        }
    }
}

@Composable
private fun AppVersionRow() {
    val context = LocalContext.current
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty().ifBlank { "Unknown" }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.MessageCircle, null, tint = T.Muted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Hermes Mobile", style = T.BodyMuted)
            Text("Version $version", style = T.Micro.copy(letterSpacing = 0.sp), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun LicensesRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(T.RadiusSmall)).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.ScrollText, null, tint = T.Muted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text("Open-source licenses", style = T.BodyMuted)
    }
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val licenseText = remember {
        runCatching {
            context.assets.open("third_party_licenses.md").bufferedReader().use { it.readText() }
        }.getOrElse { "License text unavailable." }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(T.RadiusSheet), color = T.SurfaceOne) {
            Column(Modifier.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Open-source licenses", style = T.CardTitle, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Lucide.X, "Close", tint = T.Muted, modifier = Modifier.size(17.dp)) }
                }
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    Text(licenseText, style = T.MonoSmall)
                }
            }
        }
    }
}

@Composable
private fun ConnectionBadge(phase: HostConnectionPhase) {
    val label = phaseLabel(phase)
    val color = phaseColor(phase)
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = 0.1f)).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = T.MicroBold.copy(color = color))
    }
}

@Composable
private fun FeatureChip(label: String, enabled: Boolean) {
    Text(
        label.uppercase(),
        style = T.Micro.copy(color = if (enabled) T.Cream else T.Muted, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        modifier = Modifier.clip(CircleShape).background(if (enabled) T.Cream.copy(alpha = 0.065f) else T.SurfaceOne).padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun ScreenHeading(
    title: String,
    subtitle: String,
    actionIcon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.ScreenTitle)
            Text(subtitle, style = T.Micro.copy(letterSpacing = 0.sp), modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (actionIcon != null && actionLabel != null && onAction != null) {
            IconButton(onClick = onAction, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(T.RadiusCard)).background(T.Cream.copy(alpha = 0.065f))) {
                Icon(actionIcon, actionLabel, tint = T.Cream, modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun EmptyListState(icon: ImageVector, title: String, copy: String) {
    Column(Modifier.fillMaxWidth().padding(top = 72.dp, start = 30.dp, end = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(T.RadiusSheet)).background(T.Cream.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = T.Cream, modifier = Modifier.size(23.dp)) }
        Text(title, style = T.CardTitle, modifier = Modifier.padding(top = 14.dp))
        Text(copy, style = T.BodyMuted, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, style = T.Label.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = T.Micro, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun HostStatusRow(icon: ImageVector, title: String, detail: String, state: String, stateColor: Color) {
    Card(modifier = Modifier.padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = T.SurfaceLow), border = BorderStroke(1.dp, T.Line), shape = RoundedCornerShape(T.RadiusCard)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(35.dp).clip(RoundedCornerShape(T.RadiusSmall)).background(T.Cream.copy(alpha = 0.055f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = T.Cream, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = T.Label)
                Text(detail, style = T.BodyMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            Text(state, style = T.MicroBold.copy(color = stateColor))
        }
    }
}

@Composable
private fun PrimaryButton(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = T.ControlMin).clip(RoundedCornerShape(T.RadiusCard))
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) T.AccentFill else T.Muted.copy(alpha = 0.16f),
        contentColor = if (enabled) T.OnAccent else T.Muted,
        shape = RoundedCornerShape(T.RadiusCard),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = T.Label.copy(color = if (enabled) T.OnAccent else T.Muted, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun BottomDock(selected: DeckScreen, onSelect: (DeckScreen) -> Unit) {
    HorizontalDivider(color = T.Line)
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 5.dp, vertical = 6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        listOf(
            Triple(DeckScreen.Chat, Lucide.MessageCircle, "Chat"),
            Triple(DeckScreen.Sessions, Lucide.History, "Sessions"),
            Triple(DeckScreen.Jobs, Lucide.CalendarClock, "Jobs"),
            Triple(DeckScreen.Host, Lucide.Server, "Host"),
            Triple(DeckScreen.Settings, Lucide.Settings, "Settings"),
        ).forEach { (screen, icon, label) ->
            val active = screen == selected
            Column(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(T.RadiusCard))
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        onClick = { onSelect(screen) },
                    )
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, label, tint = if (active) T.Cream else T.Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(3.dp))
                Text(label, style = T.Micro.copy(color = if (active) T.Cream else T.Muted, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, letterSpacing = 0.sp))
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
    onEdit: (String) -> Unit,
) {
    val editing = state.editingHost
    var name by remember(state.showHostPicker, state.editingHostId) { mutableStateOf(editing?.name.orEmpty()) }
    var baseUrl by remember(state.showHostPicker, state.editingHostId) { mutableStateOf(editing?.baseUrl ?: "https://") }
    var apiKey by remember(state.showHostPicker, state.editingHostId) { mutableStateOf("") }
    var allowHttp by remember(state.showHostPicker, state.editingHostId) { mutableStateOf(editing?.allowInsecureHttp ?: false) }
    var deleteTarget by remember { mutableStateOf<HostProfile?>(null) }
    var confirmDiscard by remember(state.showHostPicker, state.editingHostId) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }
    val validation = remember(editing, name, baseUrl, apiKey, allowHttp) {
        validateHostDraft(editing, name, baseUrl, apiKey, allowHttp)
    }
    val dirty = name != editing?.name.orEmpty() ||
        baseUrl != (editing?.baseUrl ?: "https://") ||
        apiKey.isNotBlank() ||
        allowHttp != (editing?.allowInsecureHttp ?: false)
    val requestDismiss = {
        if (state.hosts.isNotEmpty()) {
            if (dirty) confirmDiscard = true else onDismiss()
        }
    }
    val submit = {
        if (validation.isValid) onSave(editing?.id, name, baseUrl, apiKey, allowHttp)
    }

    ModalBottomSheet(
        onDismissRequest = requestDismiss,
        sheetState = sheetState,
        containerColor = T.SurfaceLow,
        scrimColor = T.Scrim,
        dragHandle = { Box(Modifier.padding(top = 9.dp, bottom = 4.dp).size(width = 38.dp, height = 4.dp).clip(CircleShape).background(T.LineStrong)) },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.hosts.isEmpty()) "Connect Hermes" else "Choose a host", style = T.SheetTitle)
                    Text("Switch between desktop and server instances without reconfiguring the app.", style = T.BodyMuted, modifier = Modifier.padding(top = 5.dp))
                }
                if (state.hosts.isNotEmpty()) IconButton(onClick = requestDismiss, modifier = Modifier.size(T.ControlMin)) { Icon(Lucide.X, "Close host picker", tint = T.Muted) }
            }

            if (state.hosts.isNotEmpty()) {
                Text("SAVED HOSTS", style = T.Micro, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                state.hosts.forEach { host ->
                    SavedHostRow(
                        host = host,
                        selected = state.activeHostId == host.id,
                        phase = if (state.activeHostId == host.id) state.connectionPhase else HostConnectionPhase.NoHost,
                        onSelect = { onSelect(host.id) },
                        onEdit = { onEdit(host.id) },
                        onDelete = { deleteTarget = host },
                    )
                    Spacer(Modifier.height(7.dp))
                }
                HorizontalDivider(color = T.Line, modifier = Modifier.padding(vertical = 15.dp))
            } else {
                SecurityCallout()
            }

            Text(if (editing == null) "ADD A HOST" else "EDIT HOST", style = T.Micro, modifier = Modifier.padding(top = 6.dp, bottom = 9.dp))
            HostTextField(
                value = name,
                onValueChange = { name = it },
                label = "Host name",
                placeholder = "Ubuntu Hermes",
                icon = Lucide.Server,
                error = validation.name,
                focusRequester = nameFocus,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { urlFocus.requestFocus() }),
            )
            Spacer(Modifier.height(9.dp))
            HostTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = "Hermes server URL",
                placeholder = "https://hermes.example.com",
                icon = Lucide.Globe,
                error = validation.baseUrl,
                focusRequester = urlFocus,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { keyFocus.requestFocus() }),
            )
            Spacer(Modifier.height(9.dp))
            HostTextField(
                apiKey,
                { apiKey = it },
                "API key",
                if (editing == null) "Required bearer token" else "Leave blank to keep the current key",
                Lucide.KeyRound,
                password = true,
                error = validation.apiKey,
                focusRequester = keyFocus,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow private-network HTTP", style = T.Label)
                    Text("Only for a trusted LAN or VPN. HTTPS is recommended.", style = T.BodyMuted, modifier = Modifier.padding(top = 3.dp))
                }
                Switch(
                    checked = allowHttp,
                    onCheckedChange = { allowHttp = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = T.OnAccent, checkedTrackColor = T.Cream, uncheckedThumbColor = T.Muted, uncheckedTrackColor = T.SurfaceTwo),
                )
            }
            PrimaryButton(
                label = if (editing == null) "Save and connect" else "Save changes",
                icon = Lucide.Wifi,
                enabled = validation.isValid,
                onClick = submit,
            )
            Text("Hermes Mobile probes /v1/capabilities before loading sessions. The API key is encrypted with Android Keystore and never shown again.", style = T.Micro.copy(letterSpacing = 0.sp), modifier = Modifier.padding(top = 11.dp))
        }
    }

    deleteTarget?.let { host ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = T.SurfaceLow,
            title = { Text("Delete ${host.name}?", fontSize = 16.sp) },
            text = { Text("This removes the host and its stored API key from this phone. The key is not shown anywhere, so you will need it again to re-add the host.", style = T.Body.copy(fontSize = 13.sp, lineHeight = 18.sp)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(host.id)
                    deleteTarget = null
                }) { Text("Delete", style = T.BodyMuted.copy(color = T.Error, fontSize = 13.sp)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", style = T.BodyMuted.copy(fontSize = 13.sp)) }
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = T.SurfaceLow,
            title = { Text("Discard host changes?", style = T.CardTitle) },
            text = { Text("Your unsaved host details will be lost.", style = T.Body) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDismiss()
                }) { Text("Discard", style = T.Action.copy(color = T.Error)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing", style = T.Action) }
            },
        )
    }
}

@Composable
private fun SecurityCallout() {
    Card(colors = CardDefaults.cardColors(containerColor = T.Cream.copy(alpha = 0.055f)), border = BorderStroke(1.dp, T.Cream.copy(alpha = 0.14f)), shape = RoundedCornerShape(T.RadiusSheet), modifier = Modifier.padding(top = 17.dp, bottom = 16.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Icon(Lucide.Lock, null, tint = T.Cream, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Your host stays in control", style = T.Label)
                Text("The phone is only a client. Hermes, tools, memory, and credentials remain on the selected host.", style = T.BodyMuted, modifier = Modifier.padding(top = 4.dp))
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) T.Cream.copy(alpha = 0.07f) else T.SurfaceOne),
        border = BorderStroke(1.dp, if (selected) T.FocusRing else T.Line),
        shape = RoundedCornerShape(T.RadiusCard),
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(T.RadiusSmall)).background(T.Cream.copy(alpha = 0.075f)), contentAlignment = Alignment.Center) { Icon(Lucide.Server, null, tint = T.Cream, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(host.name, style = T.Label)
                Text(host.baseUrl, style = T.MonoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            if (selected) {
                Icon(
                    if (phase == HostConnectionPhase.Connected) Lucide.CircleCheck else Lucide.RefreshCw,
                    phaseLabel(phase).lowercase(),
                    tint = phaseColor(phase),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) { Icon(Lucide.Pencil, "Edit host ${host.name}", tint = T.Muted, modifier = Modifier.size(17.dp)) }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) { Icon(Lucide.Trash2, "Delete host ${host.name}", tint = T.Muted, modifier = Modifier.size(17.dp)) }
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
    error: String? = null,
    focusRequester: FocusRequester? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        label = { Text(label, style = T.BodyMuted) },
        placeholder = { Text(placeholder, style = T.BodyMuted) },
        leadingIcon = { Icon(icon, null, tint = T.Cream, modifier = Modifier.size(18.dp)) },
        trailingIcon = if (password) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.size(T.ControlMin)) {
                    Icon(
                        if (passwordVisible) Lucide.EyeOff else Lucide.Eye,
                        if (passwordVisible) "Hide API key" else "Show API key",
                        tint = T.Muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else null,
        supportingText = error?.let { message -> { Text(message, style = T.BodyMuted.copy(color = T.Error)) } },
        isError = error != null,
        singleLine = true,
        visualTransformation = if (password && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(T.RadiusCard),
        textStyle = T.Body.copy(color = T.TextSoft),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = T.Cream.copy(alpha = 0.6f),
            unfocusedBorderColor = T.FocusRing,
            focusedLabelColor = T.Cream,
            unfocusedLabelColor = T.Muted,
            cursorColor = T.Cream,
            errorBorderColor = T.Error,
            errorLabelColor = T.Error,
            errorCursorColor = T.Error,
            focusedContainerColor = T.SurfaceLow,
            unfocusedContainerColor = T.SurfaceLow,
        ),
    )
}
