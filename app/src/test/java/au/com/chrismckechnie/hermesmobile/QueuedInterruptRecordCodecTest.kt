package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Test

class QueuedInterruptRecordCodecTest {
    @Test
    fun `queued mode survives persistence`() {
        val records = listOf(
            QueuedInterruptRecord("host", "session", "run", "continue later", FollowUpMode.Queue),
        )

        assertEquals(records, QueuedInterruptRecordCodec.decode(QueuedInterruptRecordCodec.encode(records)))
    }

    @Test
    fun `legacy queued interrupt defaults to interrupt mode`() {
        val legacy = """[{"hostId":"host","sessionId":"session","runId":"run","text":"change direction"}]"""

        assertEquals(
            FollowUpMode.Interrupt,
            QueuedInterruptRecordCodec.decode(legacy).single().mode,
        )
    }
}
