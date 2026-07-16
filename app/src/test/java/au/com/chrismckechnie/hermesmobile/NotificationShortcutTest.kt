package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationShortcutTest {
    @Test
    fun `shortcut limit failure does not escape notification publishing`() {
        val failure = IllegalArgumentException("Max number of dynamic shortcuts exceeded")
        var recorded: RuntimeException? = null

        publishShortcutSafely(
            update = { throw failure },
            onFailure = { recorded = it },
        )

        assertEquals(failure, recorded)
    }

    @Test
    fun `evicts the oldest dynamic shortcut when the device limit is full`() {
        val existing = listOf(
            DynamicShortcutSlot("newest", 300),
            DynamicShortcutSlot("oldest", 100),
            DynamicShortcutSlot("middle", 200),
        )

        assertEquals(
            listOf("oldest"),
            shortcutIdsToRemoveForCapacity(existing, incomingId = "incoming", maxCount = 3),
        )
    }

    @Test
    fun `does not evict when updating an existing shortcut`() {
        val existing = listOf(
            DynamicShortcutSlot("current", 100),
            DynamicShortcutSlot("other", 200),
        )

        assertEquals(
            emptyList<String>(),
            shortcutIdsToRemoveForCapacity(existing, incomingId = "current", maxCount = 2),
        )
    }

    @Test
    fun `evicts enough oldest shortcuts when already over the device limit`() {
        val existing = listOf(
            DynamicShortcutSlot("first", 100),
            DynamicShortcutSlot("second", 200),
            DynamicShortcutSlot("third", 300),
            DynamicShortcutSlot("fourth", 400),
        )

        assertEquals(
            listOf("first", "second"),
            shortcutIdsToRemoveForCapacity(existing, incomingId = "incoming", maxCount = 3),
        )
    }

    @Test
    fun `uses bounded push on Android 11 and newer`() {
        var pushCount = 0
        var addCount = 0

        publishDynamicShortcut(
            sdkInt = 30,
            incomingId = "incoming",
            maxCount = 1,
            existing = listOf(DynamicShortcutSlot("existing", 100)),
            rateLimitingActive = false,
            push = { pushCount += 1 },
            remove = { error("Android 11 must let push handle eviction") },
            add = { addCount += 1; true },
        )

        assertEquals(1, pushCount)
        assertEquals(0, addCount)
    }

    @Test
    fun `does not remove Android 10 shortcuts while rate limited`() {
        var removeCount = 0
        var addCount = 0

        publishDynamicShortcut(
            sdkInt = 29,
            incomingId = "incoming",
            maxCount = 1,
            existing = listOf(DynamicShortcutSlot("existing", 100)),
            rateLimitingActive = true,
            push = { error("Android 10 cannot push shortcuts") },
            remove = { removeCount += 1 },
            add = { addCount += 1; true },
        )

        assertEquals(0, removeCount)
        assertEquals(0, addCount)
    }

    @Test
    fun `evicts then adds on Android 10 when capacity is full`() {
        var removed = emptyList<String>()
        var addCount = 0

        publishDynamicShortcut(
            sdkInt = 29,
            incomingId = "incoming",
            maxCount = 2,
            existing = listOf(
                DynamicShortcutSlot("oldest", 100),
                DynamicShortcutSlot("newest", 200),
            ),
            rateLimitingActive = false,
            push = { error("Android 10 cannot push shortcuts") },
            remove = { removed = it },
            add = { addCount += 1; true },
        )

        assertEquals(listOf("oldest"), removed)
        assertEquals(1, addCount)
    }
}
