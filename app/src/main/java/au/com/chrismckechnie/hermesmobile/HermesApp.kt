package au.com.chrismckechnie.hermesmobile

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WorkOutline
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
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
private val Muted = Color(0xFF8FA69D)
private val SoftText = Color(0xFFC7D7D0)
private val Amber = Color(0xFFFFC56E)
private val Red = Color(0xFFFF817B)
private val Cyan = Color(0xFF72D8FF)

private fun phaseLabel(phase: HostConnectionPhase): String = when (phase) {
    HostConnectionPhase.Connected -> "ONLINE"
    HostConnectionPhase.Connecting -> "CONNECTING"
    HostConnectionPhase.Failed -> "OFFLINE"
    HostConnectionPhase.NoHost -> "NO HOST"
}

private fun phaseColor(phase: HostConnectionPhase): Color = when (phase) {
    HostConnectionPhase.Connected -> Mint
    HostConnectionPhase.Connecting -> Amber
    HostConnectionPhase.Failed -> Red
    HostConnectionPhase.NoHost -> Muted
}

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
                            DeckScreen.Jobs -> JobsScreen(state, viewModel)
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
                        onEdit = viewModel::editHost,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandHeader(state: HermesUiState, onChooseHost: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 17.dp),
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
                Text("HERMES", fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Text("Mobile command deck", color = Muted, fontSize = 11.sp)
            }
        }

        val hostName = state.activeHost?.name ?: "Choose host"
        val statusText = phaseLabel(state.connectionPhase)
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Mint.copy(alpha = 0.055f))
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
                    color = if (state.activeHost == null) SoftText else MintSoft,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp),
                )
                Text(statusText, color = phaseColor(state.connectionPhase), fontSize = 11.sp, letterSpacing = 0.8.sp)
            }
            Icon(Icons.Outlined.ExpandMore, null, tint = Muted, modifier = Modifier.size(18.dp))
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
            Modifier.fillMaxWidth().background(Amber.copy(alpha = 0.055f)).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Amber, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(9.dp))
            Text("Connecting to ${state.activeHost?.name ?: "Hermes"}…", color = Amber, fontSize = 12.sp)
        }
    }
    AnimatedVisibility(visible = state.connectionPhase == HostConnectionPhase.Failed || state.errorMessage != null) {
        Row(
            Modifier.fillMaxWidth().background(Red.copy(alpha = 0.075f)).padding(start = 14.dp, end = 5.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CloudOff, null, tint = Red, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(9.dp))
            Text(
                state.errorMessage ?: "Hermes host is unavailable.",
                color = Color(0xFFFFC8C5),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
            if (state.connectionPhase == HostConnectionPhase.Failed) {
                TextButton(onClick = onRetry) { Text("Retry", color = Red, fontSize = 12.sp) }
                TextButton(onClick = onManage) { Text("Hosts", color = SoftText, fontSize = 12.sp) }
            } else {
                IconButton(onClick = onDismissError, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Dismiss error", tint = Muted, modifier = Modifier.size(18.dp)) }
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
                Text("ACTIVE THREAD", color = Muted, fontSize = 11.sp, letterSpacing = 1.25.sp)
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
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Mint.copy(alpha = 0.07f)),
            ) { Icon(Icons.Outlined.Add, "New session", tint = if (state.connectionPhase == HostConnectionPhase.Connected) Mint else Muted) }
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
            fontSize = 12.sp,
            lineHeight = 17.sp,
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
            fontSize = 15.sp,
            lineHeight = 21.sp,
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
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.4.dp, color = Mint)
                    Spacer(Modifier.width(8.dp))
                    Text("Hermes is working…", color = Muted, fontSize = 12.sp)
                }
            } else {
                MarkdownText(text, modifier = Modifier.padding(top = 2.dp))
                if (streaming) {
                    Text("STREAMING", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 6.dp))
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
                    color = Color(0xFFF0F8F4),
                    fontSize = if (block.level <= 2) 17.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                )
                is MarkdownBlock.Paragraph -> Text(
                    inlineAnnotated(block.text),
                    color = SoftText,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
                is MarkdownBlock.Bullet -> Row {
                    Text("•", color = Mint, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(inlineAnnotated(block.text), color = SoftText, fontSize = 15.sp, lineHeight = 22.sp)
                }
                is MarkdownBlock.Code -> Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF081210)),
                    border = BorderStroke(1.dp, Line),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(block.language?.uppercase() ?: "CODE", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(block.code)) },
                                modifier = Modifier.size(48.dp),
                            ) { Icon(Icons.Outlined.ContentCopy, "Copy code", tint = Muted, modifier = Modifier.size(16.dp)) }
                        }
                        Text(
                            block.code,
                            color = Color(0xFFD8EFE4),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
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

private fun inlineAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    parseInlineMarkdown(text).forEach { token ->
        val style = SpanStyle(
            fontWeight = if (token.bold) FontWeight.Bold else null,
            fontStyle = if (token.italic) FontStyle.Italic else null,
            fontFamily = if (token.code) FontFamily.Monospace else null,
            background = if (token.code) Color(0x14FFFFFF) else Color.Unspecified,
        )
        if (token.linkUrl != null) {
            withLink(
                LinkAnnotation.Url(
                    token.linkUrl,
                    TextLinkStyles(style = SpanStyle(color = Cyan, textDecoration = TextDecoration.Underline)),
                )
            ) { append(token.text) }
        } else {
            withStyle(style) { append(token.text) }
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
                Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(item.preview ?: if (item.running) "Running…" else "Completed", color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (item.running) CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.5.dp, color = Cyan)
            else Text(if (item.failed) "FAILED" else "DONE", color = if (item.failed) Red else Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ApprovalCard(item: ChatUiItem.Approval, onRespond: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.padding(start = 39.dp).fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Amber.copy(alpha = 0.06f)),
        border = BorderStroke(1.dp, Amber.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, tint = Amber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
                Text("Approval required", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Amber)
            }
            item.toolName?.let {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = SoftText, modifier = Modifier.padding(top = 7.dp))
            }
            item.message?.let {
                Text(it, color = SoftText, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp))
            }
            when (item.decision) {
                null -> Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable { onRespond(true) },
                        color = Mint,
                        contentColor = Ink,
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text("Approve", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable { onRespond(false) },
                        color = Color.Transparent,
                        contentColor = Red,
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, Red.copy(alpha = 0.5f)),
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text("Deny", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                true -> Text("APPROVED", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                false -> Text("DENIED", color = Red, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            }
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
            textStyle = TextStyle(color = Color(0xFFE8F1ED), fontSize = 15.sp, lineHeight = 21.sp),
            cursorBrush = SolidColor(Mint),
            modifier = Modifier.weight(1f),
            maxLines = 4,
            decorationBox = { inner ->
                Box(Modifier.padding(vertical = 8.dp)) {
                    if (state.composerText.isBlank()) Text(
                        if (state.activeHost == null) "Choose a host to begin" else if (state.connectionPhase != HostConnectionPhase.Connected) "Waiting for host…" else "Message Hermes…",
                        color = Muted,
                        fontSize = 15.sp,
                    )
                    inner()
                }
            },
        )
        Spacer(Modifier.width(7.dp))
        if (state.isSending) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Red.copy(alpha = 0.12f)).clickable { viewModel.cancelRun() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Stop, "Stop run", tint = Red, modifier = Modifier.size(20.dp)) }
        } else {
            val canSend = enabled && state.composerText.isNotBlank()
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (canSend) Mint else Muted.copy(alpha = 0.12f)).clickable(enabled = canSend) {
                    focusManager.clearFocus()
                    viewModel.sendMessage()
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = if (canSend) Ink else Muted, modifier = Modifier.size(19.dp)) }
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
        ScreenHeading("Sessions", "${state.sessions.size} on ${state.activeHost?.name ?: "no host"}", Icons.Outlined.Add, "New session", viewModel::createSession)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            if (state.sessions.isEmpty()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyListState(Icons.Outlined.History, "No sessions yet", "Start a message and Hermes will create one here. Pull down to refresh.")
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
                            ) { Text("Load older sessions", color = Mint, fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }

    actionTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            containerColor = InkRaised,
            title = { Text(session.title?.takeIf { it.isNotBlank() } ?: "Untitled session", fontSize = 16.sp) },
            text = { Text("Rename this session or delete it from the host.", color = Muted, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    renameText = session.title.orEmpty()
                    renameTarget = session
                    actionTarget = null
                }) { Text("Rename", color = Mint, fontSize = 13.sp) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(session.id)
                    actionTarget = null
                }) { Text("Delete", color = Red, fontSize = 13.sp) }
            },
        )
    }

    renameTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = InkRaised,
            title = { Text("Rename session", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFFEAF4EF)),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSession(session.id, renameText)
                    renameTarget = null
                }) { Text("Save", color = Mint, fontSize = 13.sp) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = Muted, fontSize = 13.sp) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(session: HermesSession, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Session actions"),
        colors = CardDefaults.cardColors(containerColor = if (selected) Mint.copy(alpha = 0.075f) else Color(0xFF0A1513)),
        border = BorderStroke(1.dp, if (selected) Mint.copy(alpha = 0.28f) else Line),
        shape = RoundedCornerShape(15.dp),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) Mint else Muted.copy(alpha = 0.4f)))
                Spacer(Modifier.width(9.dp))
                Text(session.title?.takeIf { it.isNotBlank() } ?: "Untitled session", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${session.messageCount ?: 0} MSG", color = Muted, fontSize = 11.sp)
            }
            Text(session.preview?.takeIf { it.isNotBlank() } ?: "No preview available", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 17.dp))
            Text(listOfNotNull(session.source, session.model).joinToString(" · ").ifBlank { "Hermes session" }, color = Muted.copy(alpha = 0.72f), fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp, start = 17.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobsScreen(state: HermesUiState, viewModel: HermesViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 15.dp)) {
        ScreenHeading("Jobs", "Scheduled work from the selected host", Icons.Outlined.Refresh, "Refresh jobs", viewModel::refresh)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            if (state.jobs.isEmpty()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyListState(Icons.Outlined.WorkOutline, "No scheduled jobs", "Jobs exposed by this Hermes host will appear here. Pull down to refresh.")
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
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1513)), border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(job.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (job.enabled) "ACTIVE" else "PAUSED",
                        color = if (job.enabled) Mint else Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(job.schedule, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Text(job.deliver?.let { "Delivery · $it" } ?: "Local delivery", color = Muted.copy(alpha = 0.75f), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            IconButton(onClick = onRunNow, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.PlayArrow, "Run job now", tint = Cyan, modifier = Modifier.size(20.dp))
            }
            Switch(
                checked = job.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.semantics { contentDescription = if (job.enabled) "Pause job ${job.name}" else "Resume job ${job.name}" },
                colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Mint, uncheckedThumbColor = Muted, uncheckedTrackColor = SurfaceTwo),
            )
        }
    }
}

@Composable
private fun HostScreen(state: HermesUiState, viewModel: HermesViewModel) {
    val host = state.activeHost
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp)) {
        ScreenHeading("Host", "Connection and capability status", Icons.Outlined.Dns, "Manage hosts", viewModel::showHostPicker)
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
                Text(host.baseUrl, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth().padding(top = 13.dp)) {
                    Metric(state.sessions.size.toString() + if (state.sessionsHasMore) "+" else "", "SESSIONS", Modifier.weight(1f))
                    Metric(state.jobs.count { it.enabled }.toString(), "ACTIVE JOBS", Modifier.weight(1f))
                    Metric(state.capabilities?.model ?: "—", "MODEL", Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HostStatusRow(Icons.Outlined.Wifi, "Hermes API", state.capabilities?.platform ?: "Waiting for capabilities", if (state.connectionPhase == HostConnectionPhase.Connected) "READY" else "OFFLINE", if (state.connectionPhase == HostConnectionPhase.Connected) Mint else Red)
        HostStatusRow(Icons.Outlined.Shield, "Authentication", "Bearer key stored with Android Keystore encryption", "SECURE", Mint)
        HostStatusRow(Icons.Outlined.Language, "Transport", if (host.baseUrl.startsWith("https")) "HTTPS encrypted connection" else "Explicit private-network HTTP", if (host.baseUrl.startsWith("https")) "TLS" else "PRIVATE", if (host.baseUrl.startsWith("https")) Mint else Amber)
        Text("DISCOVERED CAPABILITIES", color = Muted, fontSize = 11.sp, letterSpacing = 1.1.sp, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
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
    val label = phaseLabel(phase)
    val color = phaseColor(phase)
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = 0.09f)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FeatureChip(label: String, enabled: Boolean) {
    Text(label.uppercase(), color = if (enabled) Mint else Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(CircleShape).background(if (enabled) Mint.copy(alpha = 0.065f) else SurfaceOne).padding(horizontal = 11.dp, vertical = 8.dp))
}

@Composable
private fun ScreenHeading(title: String, subtitle: String, actionIcon: ImageVector, actionLabel: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.45).sp)
            Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onAction, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Mint.copy(alpha = 0.065f))) {
            Icon(actionIcon, actionLabel, tint = Mint, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun EmptyListState(icon: ImageVector, title: String, copy: String) {
    Column(Modifier.fillMaxWidth().padding(top = 72.dp, start = 30.dp, end = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Mint.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Mint, modifier = Modifier.size(23.dp)) }
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Text(copy, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = Muted, fontSize = 11.sp, letterSpacing = 0.65.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun HostStatusRow(icon: ImageVector, title: String, detail: String, state: String, stateColor: Color) {
    Card(modifier = Modifier.padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1513)), border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(35.dp).clip(RoundedCornerShape(11.dp)).background(Mint.copy(alpha = 0.055f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Mint, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            Text(state, color = stateColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick), color = Mint, contentColor = Ink, shape = RoundedCornerShape(14.dp)) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable { onSelect(screen) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, label, tint = if (active) Mint else Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(3.dp))
                Text(label, color = if (active) Mint else Muted, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
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
        containerColor = InkRaised,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        dragHandle = { Box(Modifier.padding(top = 9.dp, bottom = 4.dp).size(width = 38.dp, height = 4.dp).clip(CircleShape).background(LineStrong)) },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.hosts.isEmpty()) "Connect Hermes" else "Choose a host", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp)
                    Text("Switch between desktop and server instances without reconfiguring the app.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
                }
                if (state.hosts.isNotEmpty()) IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Close host picker", tint = Muted) }
            }

            if (state.hosts.isNotEmpty()) {
                Text("SAVED HOSTS", color = Muted, fontSize = 11.sp, letterSpacing = 1.15.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
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
                HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 15.dp))
            } else {
                SecurityCallout()
            }

            Text(if (editing == null) "ADD A HOST" else "EDIT HOST", color = Muted, fontSize = 11.sp, letterSpacing = 1.15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 9.dp))
            HostTextField(name, { name = it }, "Host name", "Ubuntu Hermes", Icons.Outlined.Dns)
            Spacer(Modifier.height(9.dp))
            HostTextField(baseUrl, { baseUrl = it }, "Hermes server URL", "https://hermes.example.com", Icons.Outlined.Language)
            Spacer(Modifier.height(9.dp))
            HostTextField(
                apiKey,
                { apiKey = it },
                "API key",
                if (editing == null) "Required bearer token" else "Leave blank to keep the current key",
                Icons.Outlined.Key,
                password = true,
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow private-network HTTP", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Only for a trusted LAN or VPN. HTTPS is recommended.", color = Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Switch(
                    checked = allowHttp,
                    onCheckedChange = { allowHttp = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Mint, uncheckedThumbColor = Muted, uncheckedTrackColor = SurfaceTwo),
                )
            }
            PrimaryButton(if (editing == null) "Save and connect" else "Save changes", Icons.Outlined.Wifi) { onSave(editing?.id, name, baseUrl, apiKey, allowHttp) }
            Text("Hermes Mobile probes /v1/capabilities before loading sessions. The API key is encrypted with Android Keystore and never shown again.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 11.dp))
        }
    }

    deleteTarget?.let { host ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = InkRaised,
            title = { Text("Delete ${host.name}?", fontSize = 16.sp) },
            text = { Text("This removes the host and its stored API key from this phone. The key is not shown anywhere, so you will need it again to re-add the host.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(host.id)
                    deleteTarget = null
                }) { Text("Delete", color = Red, fontSize = 13.sp) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = Muted, fontSize = 13.sp) }
            },
        )
    }
}

@Composable
private fun SecurityCallout() {
    Card(colors = CardDefaults.cardColors(containerColor = Mint.copy(alpha = 0.055f)), border = BorderStroke(1.dp, Mint.copy(alpha = 0.14f)), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(top = 17.dp, bottom = 16.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Lock, null, tint = Mint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Your host stays in control", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("The phone is only a client. Hermes, tools, memory, and credentials remain on the selected host.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
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
        colors = CardDefaults.cardColors(containerColor = if (selected) Mint.copy(alpha = 0.07f) else SurfaceOne),
        border = BorderStroke(1.dp, if (selected) Mint.copy(alpha = 0.25f) else Line),
        shape = RoundedCornerShape(15.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Mint.copy(alpha = 0.075f)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Dns, null, tint = Mint, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(host.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(host.baseUrl, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            if (selected) {
                Icon(
                    if (phase == HostConnectionPhase.Connected) Icons.Outlined.CheckCircle else Icons.Outlined.Refresh,
                    phaseLabel(phase).lowercase(),
                    tint = phaseColor(phase),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Edit, "Edit host ${host.name}", tint = Muted, modifier = Modifier.size(17.dp)) }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.DeleteOutline, "Delete host ${host.name}", tint = Muted, modifier = Modifier.size(17.dp)) }
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
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = Muted, fontSize = 12.sp) },
        leadingIcon = { Icon(icon, null, tint = Mint, modifier = Modifier.size(18.dp)) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(14.dp),
        textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFFEAF4EF)),
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
