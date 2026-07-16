package au.com.chrismckechnie.hermesmobile

import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MobileRegistrationTest {
    private val enabledHost = host("enabled")
    private val disabledHost = host("disabled")

    @Test
    fun `host sync registers opted-in hosts and unregisters the rest`() = runBlocking {
        val operations = mutableListOf<Pair<String, MobileRegistrationAction>>()

        val report = syncMobileRegistrationHosts(
            hosts = listOf(enabledHost, disabledHost),
            enabledHostIds = setOf(enabledHost.id),
        ) { host, action ->
            operations += host.id to action
        }

        assertEquals(
            listOf(
                enabledHost.id to MobileRegistrationAction.Register,
                disabledHost.id to MobileRegistrationAction.Unregister,
            ),
            operations,
        )
        assertEquals(2, report.succeededHostIds.size)
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun `host sync reports a failed registration without blocking other hosts`() = runBlocking {
        val operations = mutableListOf<String>()

        val report = syncMobileRegistrationHosts(
            hosts = listOf(enabledHost, disabledHost),
            enabledHostIds = setOf(enabledHost.id),
        ) { host, _ ->
            operations += host.id
            if (host.id == enabledHost.id) error("host unavailable")
        }

        assertEquals(listOf(enabledHost.id, disabledHost.id), operations)
        assertEquals(setOf(disabledHost.id), report.succeededHostIds)
        assertEquals(enabledHost.id, report.failures.single().hostId)
        assertEquals(MobileRegistrationAction.Register, report.failures.single().action)
        assertFalse(report.isSuccess)
    }

    @Test
    fun `queue transition records current desired state without losing history`() {
        val current = listOf(
            MobileRegistrationStatus(
                hostId = enabledHost.id,
                desired = true,
                registered = true,
                pending = false,
                errorMessage = "Previous registration failed.",
                lastSuccessAtMillis = 100L,
                lastFailureAtMillis = 90L,
                lastFailureMessage = "Previous registration failed.",
            ),
        )

        val queued = markMobileRegistrationsPending(
            current = current,
            hostIds = setOf(enabledHost.id, disabledHost.id),
            desiredHostIds = setOf(enabledHost.id),
        ).associateBy(MobileRegistrationStatus::hostId)

        assertTrue(queued.getValue(enabledHost.id).desired)
        assertTrue(queued.getValue(enabledHost.id).registered)
        assertTrue(queued.getValue(enabledHost.id).pending)
        assertNull(queued.getValue(enabledHost.id).errorMessage)
        assertEquals(100L, queued.getValue(enabledHost.id).lastSuccessAtMillis)
        assertEquals(90L, queued.getValue(enabledHost.id).lastFailureAtMillis)
        assertEquals("Previous registration failed.", queued.getValue(enabledHost.id).lastFailureMessage)
        assertFalse(queued.getValue(disabledHost.id).desired)
        assertTrue(queued.getValue(disabledHost.id).pending)
    }

    @Test
    fun `report transition preserves registration on retryable failure and records success`() {
        val current = markMobileRegistrationsPending(
            current = emptyList(),
            hostIds = setOf(enabledHost.id, disabledHost.id),
            desiredHostIds = setOf(enabledHost.id),
        )
        val report = MobileRegistrationReport(
            succeededHostIds = setOf(disabledHost.id),
            failures = listOf(
                MobileRegistrationFailure(
                    hostId = enabledHost.id,
                    action = MobileRegistrationAction.Register,
                    cause = IOException("offline"),
                    message = "Could not reach this Hermes host.",
                    retryable = true,
                ),
            ),
        )

        val next = applyMobileRegistrationReportToStatuses(
            current = current,
            hostIds = setOf(enabledHost.id, disabledHost.id),
            desiredHostIds = setOf(enabledHost.id),
            report = report,
            nowMillis = 500L,
            willRetry = true,
        ).associateBy(MobileRegistrationStatus::hostId)

        val failed = next.getValue(enabledHost.id)
        assertFalse(failed.registered)
        assertTrue(failed.pending)
        assertEquals("Could not reach this Hermes host.", failed.errorMessage)
        assertEquals(500L, failed.lastFailureAtMillis)
        val succeeded = next.getValue(disabledHost.id)
        assertFalse(succeeded.registered)
        assertFalse(succeeded.pending)
        assertNull(succeeded.errorMessage)
        assertEquals(500L, succeeded.lastSuccessAtMillis)
        assertEquals("Notifications disabled on this host.", succeeded.lastSuccessMessage)
    }

    @Test
    fun `registration status codec round trips non-sensitive state`() {
        val statuses = listOf(
            MobileRegistrationStatus(
                hostId = enabledHost.id,
                desired = true,
                registered = false,
                pending = true,
                errorMessage = "Could not reach this Hermes host.",
                lastSuccessAtMillis = 10L,
                lastSuccessMessage = "Notifications registered with this host.",
                lastFailureAtMillis = 20L,
                lastFailureMessage = "Could not reach this Hermes host.",
            ),
        )

        assertEquals(statuses, MobileRegistrationStatusCodec.decode(MobileRegistrationStatusCodec.encode(statuses)))
    }

    @Test
    fun `worker decision retries only transient failures within the attempt limit`() {
        val retryable = MobileRegistrationReport(
            succeededHostIds = emptySet(),
            failures = listOf(
                MobileRegistrationFailure(
                    hostId = enabledHost.id,
                    action = MobileRegistrationAction.Register,
                    cause = IOException("offline"),
                    message = "Could not reach this Hermes host.",
                    retryable = true,
                ),
            ),
        )
        val permanent = retryable.copy(
            failures = retryable.failures.map { it.copy(retryable = false) },
        )

        assertEquals(MobileRegistrationWorkDecision.Retry, decideMobileRegistrationWork(retryable, runAttemptCount = 0))
        assertEquals(
            MobileRegistrationWorkDecision.Failure,
            decideMobileRegistrationWork(retryable, runAttemptCount = MOBILE_REGISTRATION_MAX_ATTEMPTS - 1),
        )
        assertEquals(MobileRegistrationWorkDecision.Failure, decideMobileRegistrationWork(permanent, runAttemptCount = 0))
        assertEquals(
            MobileRegistrationWorkDecision.Success,
            decideMobileRegistrationWork(MobileRegistrationReport(emptySet(), emptyList()), runAttemptCount = 0),
        )
    }

    @Test
    fun `failed Firebase token task returns its failure without reading the result`() {
        val failure = IllegalStateException("API disabled")

        val result = Tasks.forException<String>(failure).messagingTokenResult()

        assertTrue(result.isFailure)
        assertEquals(failure, result.exceptionOrNull())
    }

    private fun host(id: String) = HostProfile(
        id = id,
        name = id,
        baseUrl = "https://$id.example.com",
        apiKey = "test-key",
    )
}
