package au.com.chrismckechnie.hermesmobile

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
    fun representativeProductionSurface_hasNonEmptyScreenshotSmokeCapture() {
        setAppContent(activeWorkState(), width = 360, height = 800)

        composeRule.onNodeWithContentDescription(ACTIVE_WORK_DESCRIPTION).assertIsDisplayed()

        val screenshot = composeRule.onRoot().captureToImage()
        assertTrue("Screenshot width must be non-zero", screenshot.width > 0)
        assertTrue("Screenshot height must be non-zero", screenshot.height > 0)
    }

    private fun setAppContent(
        state: HermesUiState,
        width: Int? = null,
        height: Int? = null,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            if (width != null && height != null) {
                val configuration = DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp)) then
                    DeviceConfigurationOverride.FontScale(fontScale)
                DeviceConfigurationOverride(configuration) {
                    HermesMobileApp(state = state, viewModel = viewModel)
                }
            } else {
                HermesMobileApp(state = state, viewModel = viewModel)
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
    }
}
