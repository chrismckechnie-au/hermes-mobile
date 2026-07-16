package au.com.chrismckechnie.hermesmobile

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HermesComposeUiRegressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val viewModel by lazy {
        HermesViewModel(
            gateway = HermesHttpGateway(),
            hostStore = object : HostStore {
                override fun load() = HostLoadResult(HostSnapshot())
                override fun save(snapshot: HostSnapshot) = Unit
            },
        )
    }

    @Test
    fun activeWorkCentre_exposesMeaningfulSemanticsAndActions() {
        setAppContent(activeWorkState())

        composeRule
            .onNodeWithContentDescription(ACTIVE_WORK_DESCRIPTION)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithText("Active work").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close active work")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithContentDescription("Stop ${SESSION.title}")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun activeWorkItem_longPressShowsContextMenu() {
        setAppContent(activeWorkState())

        composeRule.onNodeWithContentDescription(ACTIVE_WORK_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(ACTIVE_WORK_ITEM_DESCRIPTION)
            .assertIsDisplayed()
            .performTouchInput { longClick() }

        composeRule.onNodeWithText("Open session").assertIsDisplayed()
        composeRule.onNodeWithText("Stop work").assertIsDisplayed()
    }

    @Test
    fun syncRequiredActiveWorkItem_longPressOffersRetryWithoutStop() {
        val key = SessionKey(HOST.id, SESSION.id)
        val state = activeWorkState().copy(
            activeRuns = mapOf(
                key to ActiveRun(
                    host = HOST,
                    sessionId = SESSION.id,
                    sessionTitle = SESSION.title,
                    runId = "run-1",
                    terminalUnsynced = true,
                ),
            ),
        )
        setAppContent(state)

        composeRule.onNodeWithContentDescription("1 needs attention · 1 active. Show active work.").performClick()
        composeRule.onNodeWithContentDescription(
            "Active work item ${SESSION.title}, ${HOST.name}, Transcript sync required",
        ).performTouchInput { longClick() }

        composeRule.onNodeWithText("Retry transcript sync").assertIsDisplayed()
        composeRule.onNodeWithText("Stop work").assertIsNotDisplayed()
    }

    @Test
    fun primaryActions_keepAtLeastFortyEightDpTouchTargets() {
        setAppContent(baseState())

        assertMinimumTouchTarget(HOST_DESCRIPTION)
        assertMinimumTouchTarget("New session")
        assertMinimumTouchTarget("Settings")
    }

    @Test
    fun chatKeepsCriticalControlsOnScreenAtThreeHundredTwentyDp() {
        setAppContent(baseState(), width = 320, height = 720)

        val host = composeRule.onNodeWithContentDescription(HOST_DESCRIPTION).assertIsDisplayed()
        val newSession = composeRule.onNodeWithContentDescription("New session").assertIsDisplayed()
        val settings = composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        composeRule.onNode(hasText("Message Hermes, or / for commands"), useUnmergedTree = true)
            .assertIsDisplayed()

        assertFullyInsideRoot(host, "host selector")
        assertFullyInsideRoot(newSession, "new-session action")
        assertFullyInsideRoot(settings, "settings navigation")
    }

    @Test
    fun activeWorkRemainsUsableAtLargeTextOnCompactWidth() {
        setAppContent(activeWorkState(), width = 320, height = 720, fontScale = 1.3f)

        composeRule.onNodeWithContentDescription(ACTIVE_WORK_DESCRIPTION)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithText("Active work").assertIsDisplayed()
        composeRule.onNodeWithText(SESSION.title.orEmpty()).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close active work").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop ${SESSION.title}").assertIsDisplayed()
    }

    @Test
    fun chatKeepsPrimaryNavigationAtTwoHundredPercentText() {
        setAppContent(baseState(), width = 320, height = 720, fontScale = 2f)

        val host = composeRule.onNodeWithContentDescription(HOST_DESCRIPTION).assertIsDisplayed()
        val newSession = composeRule.onNodeWithContentDescription("New session").assertIsDisplayed()
        val settings = composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        composeRule.onNodeWithText(HOST.name, useUnmergedTree = true).assertIsDisplayed()

        assertFullyInsideRoot(host, "host selector at 200% text")
        assertFullyInsideRoot(newSession, "new-session action at 200% text")
        assertFullyInsideRoot(settings, "settings navigation at 200% text")
    }

    @Test
    fun representativeProductionSurface_hasNonEmptyScreenshotSmokeCapture() {
        setAppContent(activeWorkState(), width = 360, height = 800)

        composeRule.onNodeWithContentDescription(ACTIVE_WORK_DESCRIPTION).assertIsDisplayed()

        val screenshot = composeRule.onRoot().captureToImage()
        assertTrue("Screenshot width must be non-zero", screenshot.width > 0)
        assertTrue("Screenshot height must be non-zero", screenshot.height > 0)
    }

    @Test
    fun sessionsScreen_toleratesDuplicateIdsDuringHostRefresh() {
        val duplicateSessions = listOf(
            SESSION,
            SESSION.copy(title = "Release verification refresh", lastActive = "2026-07-16T02:00:00Z"),
        )
        setAppContent(
            baseState(DeckScreen.Sessions).copy(
                sessionsResource = ResourceState.Data(duplicateSessions),
            ),
        )

        composeRule.onNodeWithText("Release verification refresh").assertIsDisplayed()
    }

    @Test
    fun hostPicker_waitsForInteractionBeforeShowingErrors() {
        setAppContent(
            HermesUiState(
                showHostPicker = true,
                themeMode = ThemeMode.Light,
            ),
        )

        composeRule.onNodeWithText("Give this host a name.").assertIsNotDisplayed()
        composeRule.onNodeWithText("Hermes API key is required.").assertIsNotDisplayed()

        composeRule.onNodeWithText("Save and connect").performClick()

        composeRule.onNodeWithText("Give this host a name.").assertIsDisplayed()
        composeRule.onNodeWithText("Hermes API key is required.").assertIsDisplayed()
    }

    @Test
    fun modelSheet_filtersAcrossEveryHostModel() {
        val models = (1..9).map { "model-$it" } + "gpt-5.6-terra"
        setAppContent(
            baseState().copy(
                capabilities = HermesCapabilities(
                    model = "hermes-agent",
                    platform = "hermes-agent",
                    features = setOf("run_reasoning_effort"),
                    defaultModel = "model-1",
                ),
                modelsResource = ResourceState.Data(models),
            ),
        )

        composeRule.onNodeWithContentDescription("Model and reasoning settings").performClick()
        composeRule.onNodeWithText("Search models").performTextInput("terra")

        composeRule.onNodeWithText("gpt-5.6-terra").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun liveReasoning_isExpandedWithoutExtraInteraction() {
        setAppContent(
            baseState().copy(
                transcriptResource = ResourceState.Data(
                    listOf(ChatUiItem.Reasoning("reasoning-1", listOf("Inspecting the latest host activity"))),
                ),
            ),
        )

        composeRule.onNodeWithText("Inspecting the latest host activity").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Collapse Hermes activity").performClick()
        assertMinimumTouchTarget("Expand Hermes activity")
    }

    @Test
    fun fallbackLiveStatus_expandsWhenProgressAccumulates() {
        setAppContent(
            baseState().copy(
                transcriptResource = ResourceState.Data(
                    listOf(
                        ChatUiItem.Assistant(
                            id = "assistant-1",
                            text = "",
                            streaming = true,
                            safeStatus = "Running verification",
                            safeStatusHistory = listOf("Inspecting the project", "Running focused tests"),
                        ),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Running focused tests").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Collapse live Hermes status").performClick()
        assertMinimumTouchTarget("Expand live Hermes status")
    }

    @Test
    fun bottomDock_retainsAccessibleTabsAtLargeText() {
        setAppContent(
            baseState(),
            width = 320,
            height = 520,
            fontScale = 2f,
        )

        composeRule.onNodeWithContentDescription("Sessions")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun modelReasoning_remainsReachableAtLargeText() {
        setAppContent(
            baseState().copy(
                capabilities = HermesCapabilities(
                    model = "hermes-agent",
                    platform = "hermes-agent",
                    features = setOf("run_reasoning_effort"),
                    defaultModel = "model-1",
                ),
                modelsResource = ResourceState.Data((1..10).map { "model-$it" }),
            ),
            width = 320,
            height = 520,
            fontScale = 2f,
        )

        composeRule.onNodeWithContentDescription("Model and reasoning settings")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        composeRule.onNodeWithText("Search models").performTextInput("10")
        composeRule.onNodeWithText("model-10").assertIsDisplayed()
        composeRule.onNodeWithText("max").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun permissionConfirmation_remainsReachableAtLargeText() {
        setAppContent(
            baseState().copy(
                capabilities = HermesCapabilities(
                    model = "hermes-agent",
                    platform = "hermes-agent",
                    features = setOf("run_permission_mode"),
                ),
                pendingFullAccessConfirmation = FullAccessConfirmation(HOST.id, SESSION.id),
            ),
            width = 320,
            height = 520,
            fontScale = 2f,
        )

        composeRule.onNodeWithContentDescription("Permission settings").performClick()
        composeRule.onNodeWithText("Confirm next run").performScrollTo().assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun dismissingPermissionSheet_clearsPendingFullAccess() {
        val permissionViewModel = HermesViewModel(
            gateway = HermesHttpGateway(),
            hostStore = object : HostStore {
                override fun load() = HostLoadResult(HostSnapshot(listOf(HOST), HOST.id))
                override fun save(snapshot: HostSnapshot) = Unit
            },
        )
        permissionViewModel.selectPermissionMode("full-access")
        assertTrue(permissionViewModel.state.value.pendingFullAccessConfirmation != null)
        setAppContent(
            baseState().copy(
                activeSessionId = null,
                capabilities = HermesCapabilities(
                    model = "hermes-agent",
                    platform = "hermes-agent",
                    features = setOf("run_permission_mode"),
                ),
                pendingFullAccessConfirmation = FullAccessConfirmation(HOST.id, null),
            ),
            targetViewModel = permissionViewModel,
        )

        composeRule.onNodeWithContentDescription("Permission settings").performClick()
        composeRule.onNodeWithText("Use Full Access once?").assertIsDisplayed()
        Espresso.pressBack()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            permissionViewModel.state.value.pendingFullAccessConfirmation == null
        }
    }

    @Test
    fun taskPlanPill_exposesButtonRole() {
        val key = SessionKey(HOST.id, SESSION.id)
        setAppContent(
            baseState().copy(
                activeRuns = mapOf(
                    key to ActiveRun(
                        host = HOST,
                        sessionId = SESSION.id,
                        sessionTitle = SESSION.title,
                        runId = "run-1",
                        tasks = listOf(HermesTask("task-1", "Verify the release", "in_progress")),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription("Show task plan, 0 / 1 tasks")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun runningSession_exposesVisibleRenameAction() {
        val key = SessionKey(HOST.id, SESSION.id)
        setAppContent(
            baseState(DeckScreen.Sessions).copy(
                capabilities = HermesCapabilities(
                    model = "hermes-agent",
                    platform = "hermes-agent",
                    features = setOf("session_resources"),
                ),
                activeRuns = mapOf(
                    key to ActiveRun(
                        host = HOST,
                        sessionId = SESSION.id,
                        sessionTitle = SESSION.title,
                        runId = "run-1",
                    ),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription("Session actions for Release verification")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithText("Rename").assertIsDisplayed().assertIsEnabled()
    }

    private fun setAppContent(
        state: HermesUiState,
        width: Int? = null,
        height: Int? = null,
        fontScale: Float = 1f,
        targetViewModel: HermesViewModel = viewModel,
    ) {
        composeRule.setContent {
            if (width != null && height != null) {
                val configuration = DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp)) then
                    DeviceConfigurationOverride.FontScale(fontScale)
                DeviceConfigurationOverride(configuration) {
                    HermesMobileApp(state = state, viewModel = targetViewModel)
                }
            } else {
                HermesMobileApp(state = state, viewModel = targetViewModel)
            }
        }
    }

    private fun assertMinimumTouchTarget(contentDescription: String) {
        val node = composeRule.onNodeWithContentDescription(contentDescription)
            .assertIsDisplayed()
            .assertHasClickAction()
            .fetchSemanticsNode()
        val minimumPixels = with(composeRule.density) { 48.dp.toPx() }
        val bounds = node.touchBoundsInRoot
        assertTrue(
            "$contentDescription touch width was ${bounds.width}px; expected at least ${minimumPixels}px",
            bounds.width >= minimumPixels - 1f,
        )
        assertTrue(
            "$contentDescription touch height was ${bounds.height}px; expected at least ${minimumPixels}px",
            bounds.height >= minimumPixels - 1f,
        )
    }

    private fun assertFullyInsideRoot(node: SemanticsNodeInteraction, label: String) {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$label starts outside the root: $bounds vs $root", bounds.left >= root.left - 1f)
        assertTrue("$label ends outside the root: $bounds vs $root", bounds.right <= root.right + 1f)
        assertTrue("$label starts above the root: $bounds vs $root", bounds.top >= root.top - 1f)
        assertTrue("$label ends below the root: $bounds vs $root", bounds.bottom <= root.bottom + 1f)
    }

    private fun baseState(screen: DeckScreen = DeckScreen.Chat) = HermesUiState(
        screen = screen,
        hosts = listOf(HOST),
        activeHostId = HOST.id,
        connectionPhase = HostConnectionPhase.Connected,
        sessionsResource = ResourceState.Data(listOf(SESSION)),
        activeSessionId = SESSION.id,
        themeMode = ThemeMode.Light,
    )

    private fun activeWorkState(): HermesUiState {
        val key = SessionKey(HOST.id, SESSION.id)
        return baseState(DeckScreen.Host).copy(
            activeRuns = mapOf(
                key to ActiveRun(
                    host = HOST,
                    sessionId = SESSION.id,
                    sessionTitle = SESSION.title,
                    runId = "run-1",
                ),
            ),
        )
    }

    private companion object {
        val HOST = HostProfile(
            id = "host-1",
            name = "Local Hermes",
            baseUrl = "https://hermes.example.test",
            apiKey = "test-key",
        )
        val SESSION = HermesSession(
            id = "session-1",
            title = "Release verification",
            preview = null,
            source = "mobile",
            model = null,
            lastActive = null,
            messageCount = 1,
        )
        const val HOST_DESCRIPTION = "Hermes host Local Hermes, online. Opens host picker."
        const val ACTIVE_WORK_DESCRIPTION =
            "1 active run · Release verification. Show active work."
        const val ACTIVE_WORK_ITEM_DESCRIPTION =
            "Active work item Release verification, Local Hermes, Working"
    }
}
