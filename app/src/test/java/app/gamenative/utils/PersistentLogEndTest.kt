package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides what a log export says about the previous run: a changed boot id means the
 * device rebooted under the session, otherwise the SESSION-END marker decides between a clean exit
 * and a kill.
 */
class PersistentLogEndTest {

    @Test
    fun aDifferentBootIdMeansTheDeviceRebooted() {
        assertEquals(
            PersistentLog.PreviousEnd.REBOOT,
            PersistentLog.classifyEnd("boot-a", "boot-b", endedCleanly = false),
        )
        // Even a clean marker cannot survive a reboot that skipped our shutdown path.
        assertEquals(
            PersistentLog.PreviousEnd.REBOOT,
            PersistentLog.classifyEnd("boot-a", "boot-b", endedCleanly = true),
        )
    }

    @Test
    fun sameBootIdWithTheSessionEndMarkerIsClean() {
        assertEquals(
            PersistentLog.PreviousEnd.CLEAN,
            PersistentLog.classifyEnd("boot-a", "boot-a", endedCleanly = true),
        )
    }

    @Test
    fun sameBootIdWithoutTheMarkerWasAKill() {
        assertEquals(
            PersistentLog.PreviousEnd.KILLED,
            PersistentLog.classifyEnd("boot-a", "boot-a", endedCleanly = false),
        )
    }

    @Test
    fun missingBootIdsFallBackToTheMarker() {
        assertEquals(
            PersistentLog.PreviousEnd.CLEAN,
            PersistentLog.classifyEnd(null, "boot-b", endedCleanly = true),
        )
        assertEquals(
            PersistentLog.PreviousEnd.KILLED,
            PersistentLog.classifyEnd(null, null, endedCleanly = false),
        )
    }
}
