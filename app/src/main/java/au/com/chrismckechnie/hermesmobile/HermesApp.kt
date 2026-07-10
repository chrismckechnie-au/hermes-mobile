package au.com.chrismckechnie.hermesmobile

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.History
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.ScrollText
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Wifi
import com.composables.icons.lucide.X

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesMobileApp(state: HermesUiState, viewModel: HermesViewModel) {
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
                                DeckScreen.Jobs -> JobsScreen(state, viewModel)
                                DeckScreen.Host -> HostScreen(state, viewModel)
                                DeckScreen.Settings -> SettingsScreen(state, viewModel)
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
                }
            }
        }
    }
}

@Composable
private fun CommandHeader(state: HermesUiState, onChooseHost: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 17.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(T.RadiusSmall)).background(T.Cream.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) { Icon(HermesWing, null, tint = T.Cream, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("HERMES", style = T.Label.copy(letterSpacing = 1.8.sp))
                Text("Mobile command deck", style = T.Micro.copy(letterSpacing = 0.sp))
            }
        }

        val hostName = state.activeHost?.name ?: "Choose host"
        val statusText = phaseLabel(state.connectionPhase)
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
                Text(statusText, style = T.Micro.copy(color = phaseColor(state.connectionPhase), letterSpacing = 0.8.sp))
            }
            Icon(Lucide.ChevronDown, null, tint = T.Muted, modifier = Modifier.size(18.dp))
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
            Modifier.fillMaxWidth().background(T.Warn.copy(alpha = 0.06f)).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = T.Warn, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(9.dp))
            Text("Connecting to ${state.activeHost?.name ?: "Hermes"}…", style = T.BodyMuted.copy(color = T.Warn))
        }
    }
    AnimatedVisibility(visible = state.connectionPhase == HostConnectionPhase.Failed || state.errorMessage != null) {
        Row(
            Modifier.fillMaxWidth().background(T.Error.copy(alpha = 0.08f)).padding(start = 14.dp, end = 5.dp, top = 7.dp, bottom = 7.dp),
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
    val lastAssistantLength = (state.messages.lastOrNull { it is ChatUiItem.Assistant } as? ChatUiItem.Assistant)?.text?.length ?: 0
    // Follow the stream: react to new items AND to the growing text of the last
    // assistant bubble, but never fight the user's own scrolling.
    LaunchedEffect(state.messages.size, lastAssistantLength) {
        if (state.messages.isNotEmpty() && !listState.isScrollInProgress) {
            listState.scrollToItem(state.messages.lastIndex, scrollOffset = 100_000)
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
                )
            }
            IconButton(
                onClick = viewModel::createSession,
                enabled = state.connectionPhase == HostConnectionPhase.Connected,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(T.RadiusCard)).background(T.Cream.copy(alpha = 0.07f)),
            ) { Icon(Lucide.Plus, "New session", tint = if (state.connectionPhase == HostConnectionPhase.Connected) T.Cream else T.Muted) }
        }

        if (state.messages.isEmpty()) {
            EmptyConversation(state, Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                items(state.messages, key = { it.id }) { item ->
                    when (item) {
                        is ChatUiItem.User -> UserBubble(item.text)
                        is ChatUiItem.Assistant -> AssistantMessage(item.text, item.streaming)
                        is ChatUiItem.Tool -> LiveToolCard(item)
                        is ChatUiItem.Approval -> ApprovalCard(item) { approve -> viewModel.respondToApproval(item.id, approve) }
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
            Modifier.size(54.dp).clip(RoundedCornerShape(T.RadiusSheet)).background(T.Cream.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) { Icon(if (state.activeHost == null) Lucide.Server else HermesWing, null, tint = T.Cream, modifier = Modifier.size(25.dp)) }
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
    }
}

@Composable
private fun UserBubble(text: String) {
    // Right-aligned, hugging: short messages get a snug bubble, long ones
    // wrap within the row width minus the start inset.
    Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            style = T.Body.copy(color = T.OnAccent),
            modifier = Modifier.clip(RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp)).background(T.BubbleUser).padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AssistantMessage(text: String, streaming: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(29.dp).clip(RoundedCornerShape(T.RadiusSmall)).background(T.Cream.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Icon(HermesWing, null, tint = T.Cream, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (text.isBlank() && streaming) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.4.dp, color = T.Cream)
                    Spacer(Modifier.width(8.dp))
                    Text("Hermes is working…", style = T.BodyMuted)
                }
            } else {
                MarkdownText(text, modifier = Modifier.padding(top = 2.dp))
                if (streaming) {
                    Text("STREAMING", style = T.MicroBold, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
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

@Composable
private fun LiveToolCard(item: ChatUiItem.Tool) {
    Card(
        modifier = Modifier.padding(start = 39.dp).fillMaxWidth(),
        shape = RoundedCornerShape(T.RadiusCard),
        colors = CardDefaults.cardColors(containerColor = T.SurfaceLow),
        border = BorderStroke(1.dp, if (item.failed) T.Error.copy(alpha = 0.3f) else T.Line),
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(33.dp).clip(RoundedCornerShape(T.RadiusSmall)).background(T.Tool.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Icon(Lucide.Terminal, null, tint = if (item.failed) T.Error else T.Tool, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = T.Label)
                Text(item.preview ?: if (item.running) "Running…" else "Completed", style = T.MonoSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (item.running) CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.5.dp, color = T.Tool)
            else Text(if (item.failed) "FAILED" else "DONE", style = T.MicroBold.copy(color = if (item.failed) T.Error else T.Cream))
        }
    }
}

@Composable
private fun ApprovalCard(item: ChatUiItem.Approval, onRespond: (Boolean) -> Unit) {
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
                Text("Approval required", style = T.CardTitle.copy(color = T.Warn))
            }
            item.toolName?.let {
                Text(it, style = T.MonoBody.copy(color = T.TextSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 7.dp))
            }
            item.message?.let {
                Text(it, style = T.Body.copy(fontSize = 13.sp, lineHeight = 18.sp), modifier = Modifier.padding(top = 5.dp))
            }
            when (item.decision) {
                null -> Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable { onRespond(true) },
                        color = T.Cream,
                        contentColor = T.OnAccent,
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text("Approve", style = T.Label.copy(color = T.OnAccent, fontWeight = FontWeight.Bold))
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable { onRespond(false) },
                        color = Color.Transparent,
                        contentColor = T.Error,
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, T.Error.copy(alpha = 0.5f)),
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text("Deny", style = T.Label.copy(color = T.Error, fontWeight = FontWeight.Bold))
                        }
                    }
                }
                true -> Text("APPROVED", style = T.MicroBold.copy(color = T.Cream), modifier = Modifier.padding(top = 10.dp))
                false -> Text("DENIED", style = T.MicroBold.copy(color = T.Error), modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun Composer(state: HermesUiState, viewModel: HermesViewModel) {
    val enabled = state.connectionPhase == HostConnectionPhase.Connected && !state.isSending
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp).clip(RoundedCornerShape(T.RadiusSheet)).background(T.SurfaceOne).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = state.composerText,
            onValueChange = viewModel::setComposerText,
            enabled = enabled,
            textStyle = T.Body.copy(color = T.TextSoft),
            cursorBrush = SolidColor(T.Cream),
            modifier = Modifier.weight(1f),
            maxLines = 4,
            decorationBox = { inner ->
                Box(Modifier.padding(vertical = 6.dp)) {
                    if (state.composerText.isBlank()) Text(
                        if (state.activeHost == null) "Choose a host to begin" else if (state.connectionPhase != HostConnectionPhase.Connected) "Waiting for host…" else "Message Hermes…",
                        style = T.Body.copy(color = T.Muted),
                    )
                    inner()
                }
            },
        )
        Spacer(Modifier.width(7.dp))
        if (state.isSending) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(T.RadiusCard)).background(T.Error.copy(alpha = 0.12f)).clickable { viewModel.cancelRun() },
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Square, "Stop run", tint = T.Error, modifier = Modifier.size(18.dp)) }
        } else {
            val canSend = enabled && state.composerText.isNotBlank()
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(T.RadiusCard)).background(if (canSend) T.Cream else T.Muted.copy(alpha = 0.12f)).clickable(enabled = canSend) {
                    focusManager.clearFocus()
                    viewModel.sendMessage()
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Send, "Send", tint = if (canSend) T.OnAccent else T.Muted, modifier = Modifier.size(19.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsScreen(state: HermesUiState, viewModel: HermesViewModel) {
    var actionTarget by remember { mutableStateOf<HermesSession?>(null) }
    var renameTarget by remember { mutableStateOf<HermesSession?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 15.dp)) {
        ScreenHeading("Sessions", "${state.sessions.size} on ${state.activeHost?.name ?: "no host"}", Lucide.Plus, "New session", viewModel::createSession)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            if (state.sessions.isEmpty()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyListState(Lucide.History, "No sessions yet", "Start a message and Hermes will create one here. Pull down to refresh.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(state.sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            selected = state.activeSessionId == session.id,
                            onClick = { viewModel.selectSession(session.id) },
                            onLongClick = { actionTarget = session },
                        )
                    }
                    if (state.sessionsHasMore) {
                        item(key = "load-more") {
                            TextButton(
                                onClick = viewModel::loadMoreSessions,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("Load older sessions", style = T.BodyMuted.copy(color = T.Cream, fontSize = 13.sp)) }
                        }
                    }
                }
            }
        }
    }

    actionTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            containerColor = T.SurfaceLow,
            title = { Text(session.title?.takeIf { it.isNotBlank() } ?: "Untitled session", fontSize = 16.sp) },
            text = { Text("Rename this session or delete it from the host.", style = T.BodyMuted.copy(fontSize = 13.sp)) },
            confirmButton = {
                TextButton(onClick = {
                    renameText = session.title.orEmpty()
                    renameTarget = session
                    actionTarget = null
                }) { Text("Rename", style = T.BodyMuted.copy(color = T.Cream, fontSize = 13.sp)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(session.id)
                    actionTarget = null
                }) { Text("Delete", style = T.BodyMuted.copy(color = T.Error, fontSize = 13.sp)) }
            },
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(session: HermesSession, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Session actions"),
        colors = CardDefaults.cardColors(containerColor = if (selected) T.Cream.copy(alpha = 0.075f) else T.SurfaceLow),
        border = BorderStroke(1.dp, if (selected) T.FocusRing else T.Line),
        shape = RoundedCornerShape(T.RadiusCard),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) T.Cream else T.Muted.copy(alpha = 0.4f)))
                Spacer(Modifier.width(9.dp))
                Text(session.title?.takeIf { it.isNotBlank() } ?: "Untitled session", style = T.Label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${session.messageCount ?: 0} MSG", style = T.Micro)
            }
            Text(session.preview?.takeIf { it.isNotBlank() } ?: "No preview available", style = T.BodyMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 17.dp))
            Text(listOfNotNull(session.source, session.model).joinToString(" · ").ifBlank { "Hermes session" }, style = T.Micro.copy(color = T.Muted.copy(alpha = 0.72f), letterSpacing = 0.sp), modifier = Modifier.padding(top = 9.dp, start = 17.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobsScreen(state: HermesUiState, viewModel: HermesViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 15.dp)) {
        ScreenHeading("Jobs", "Scheduled work from the selected host", Lucide.RefreshCw, "Refresh jobs", viewModel::refresh)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            if (state.jobs.isEmpty()) {
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
                        JobCard(job, onToggle = { viewModel.toggleJob(job) }, onRunNow = { viewModel.runJobNow(job.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun JobCard(job: HermesJob, onToggle: () -> Unit, onRunNow: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = T.SurfaceLow), border = BorderStroke(1.dp, T.Line), shape = RoundedCornerShape(T.RadiusCard)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
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
            IconButton(onClick = onRunNow, modifier = Modifier.size(48.dp)) {
                Icon(Lucide.Play, "Run job now", tint = T.Tool, modifier = Modifier.size(20.dp))
            }
            Switch(
                checked = job.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.semantics { contentDescription = if (job.enabled) "Pause job ${job.name}" else "Resume job ${job.name}" },
                colors = SwitchDefaults.colors(checkedThumbColor = T.OnAccent, checkedTrackColor = T.Cream, uncheckedThumbColor = T.Muted, uncheckedTrackColor = T.SurfaceTwo),
            )
        }
    }
}

@Composable
private fun HostScreen(state: HermesUiState, viewModel: HermesViewModel) {
    val host = state.activeHost
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
        HostStatusRow(Lucide.ShieldCheck, "Authentication", "Bearer key stored with Android Keystore encryption", "SECURE", T.Cream)
        HostStatusRow(Lucide.Globe, "Transport", if (host.baseUrl.startsWith("https")) "HTTPS encrypted connection" else "Explicit private-network HTTP", if (host.baseUrl.startsWith("https")) "TLS" else "PRIVATE", if (host.baseUrl.startsWith("https")) T.Cream else T.Warn)
        Text("DISCOVERED CAPABILITIES", style = T.Micro, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            val features = state.capabilities?.features.orEmpty().ifEmpty { setOf("Waiting for host") }
            features.sorted().take(8).forEach { FeatureChip(it.replace('_', ' '), state.connectionPhase == HostConnectionPhase.Connected) }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Choose or manage hosts", Lucide.Server, viewModel::showHostPicker)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsScreen(state: HermesUiState, viewModel: HermesViewModel) {
    var showLicenses by remember { mutableStateOf(false) }
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
        Text("ABOUT", style = T.Micro, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        Surface(color = T.SurfaceLow, border = BorderStroke(1.dp, T.Line), shape = RoundedCornerShape(T.RadiusCard)) {
            LicensesRow { showLicenses = true }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (showLicenses) LicensesDialog { showLicenses = false }
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
private fun PrimaryButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(T.RadiusCard)).clickable(onClick = onClick), color = T.Cream, contentColor = T.OnAccent, shape = RoundedCornerShape(T.RadiusCard)) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = T.Label.copy(color = T.OnAccent, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun BottomDock(selected: DeckScreen, onSelect: (DeckScreen) -> Unit) {
    HorizontalDivider(color = T.Line)
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 5.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(
            Triple(DeckScreen.Chat, Lucide.MessageCircle, "Chat"),
            Triple(DeckScreen.Sessions, Lucide.History, "Sessions"),
            Triple(DeckScreen.Jobs, Lucide.CalendarClock, "Jobs"),
            Triple(DeckScreen.Host, Lucide.Server, "Host"),
            Triple(DeckScreen.Settings, Lucide.Settings, "Settings"),
        ).forEach { (screen, icon, label) ->
            val active = screen == selected
            Column(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(T.RadiusCard)).clickable { onSelect(screen) }.padding(vertical = 7.dp),
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { if (state.hosts.isNotEmpty()) onDismiss() },
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
                if (state.hosts.isNotEmpty()) IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Lucide.X, "Close host picker", tint = T.Muted) }
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
            HostTextField(name, { name = it }, "Host name", "Ubuntu Hermes", Lucide.Server)
            Spacer(Modifier.height(9.dp))
            HostTextField(baseUrl, { baseUrl = it }, "Hermes server URL", "https://hermes.example.com", Lucide.Globe)
            Spacer(Modifier.height(9.dp))
            HostTextField(
                apiKey,
                { apiKey = it },
                "API key",
                if (editing == null) "Required bearer token" else "Leave blank to keep the current key",
                Lucide.KeyRound,
                password = true,
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
            PrimaryButton(if (editing == null) "Save and connect" else "Save changes", Lucide.Wifi) { onSave(editing?.id, name, baseUrl, apiKey, allowHttp) }
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
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, style = T.BodyMuted) },
        placeholder = { Text(placeholder, style = T.BodyMuted) },
        leadingIcon = { Icon(icon, null, tint = T.Cream, modifier = Modifier.size(18.dp)) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(T.RadiusCard),
        textStyle = T.Body.copy(color = T.TextSoft),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = T.Cream.copy(alpha = 0.6f),
            unfocusedBorderColor = T.FocusRing,
            focusedLabelColor = T.Cream,
            unfocusedLabelColor = T.Muted,
            cursorColor = T.Cream,
            focusedContainerColor = T.SurfaceLow,
            unfocusedContainerColor = T.SurfaceLow,
        ),
    )
}
