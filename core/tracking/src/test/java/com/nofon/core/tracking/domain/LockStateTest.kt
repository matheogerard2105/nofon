package com.nofon.core.tracking.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockStateTest {

    private val t0 = 1_755_700_000_000L
    private val minute = 60_000L
    private val hour = 3_600_000L

    @Test
    fun `avant tout evenement l etat par defaut est deverrouille`() {
        val timeline = buildLockStateTimeline(emptyList())
        assertFalse(isLockedAt(timeline, t0))
    }

    @Test
    fun `keyguard shown marque verrouille jusqu au keyguard hidden`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.KEYGUARD_SHOWN, null, 17, null),
            RawUsageEvent(t0 + 4 * hour, RawEventType.KEYGUARD_HIDDEN, null, 18, null)
        )
        val timeline = buildLockStateTimeline(events.sortedBy { it.timestampMillis })

        assertFalse(isLockedAt(timeline, t0 - minute))
        assertTrue(isLockedAt(timeline, t0 + hour))
        assertTrue(isLockedAt(timeline, t0 + 4 * hour - 1))
        assertFalse(isLockedAt(timeline, t0 + 4 * hour + minute))
    }

    @Test
    fun `ecran non interactif marque verrouille meme sans keyguard`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.SCREEN_NON_INTERACTIVE, null, 16, null),
            RawUsageEvent(t0 + 2 * hour, RawEventType.SCREEN_INTERACTIVE, null, 15, null)
        )
        val timeline = buildLockStateTimeline(events.sortedBy { it.timestampMillis })

        assertTrue(isLockedAt(timeline, t0 + hour))
        assertFalse(isLockedAt(timeline, t0 + 2 * hour + minute))
    }

    @Test
    fun `un evenement sans rapport entre deux changements d etat n efface pas l etat courant`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.KEYGUARD_SHOWN, null, 17, null),
            RawUsageEvent(t0 + minute, RawEventType.OTHER, "com.background.sync", 99, null)
        )
        val timeline = buildLockStateTimeline(events.sortedBy { it.timestampMillis })

        assertTrue(isLockedAt(timeline, t0 + 2 * minute))
    }

    @Test
    fun `device shutdown verrouille jusqu au startup puis reveil et keyguard hidden`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.DEVICE_SHUTDOWN, null, 26, null),
            RawUsageEvent(t0 + hour, RawEventType.DEVICE_STARTUP, null, 27, null),
            RawUsageEvent(t0 + hour + minute, RawEventType.SCREEN_INTERACTIVE, null, 15, null),
            RawUsageEvent(t0 + hour + minute + 500, RawEventType.KEYGUARD_HIDDEN, null, 18, null)
        )
        val timeline = buildLockStateTimeline(events.sortedBy { it.timestampMillis })

        assertTrue(isLockedAt(timeline, t0 + 30 * minute))
        assertTrue(isLockedAt(timeline, t0 + hour + 30_000L)) // redémarré mais toujours verrouillé
        assertFalse(isLockedAt(timeline, t0 + hour + 2 * minute))
    }
}
