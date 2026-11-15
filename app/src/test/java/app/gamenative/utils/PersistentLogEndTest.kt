package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that decide what a log export says about the previous run and what the storage
 * optimizer is allowed to delete.
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

class GameStorageOptimizerJunkTest {
    private val now = 1_700_000_000_000L
    private val fresh = now - 1_000L
    private val stale = now - GameStorageOptimizer.JUNK_LOG_AGE_MS - 1_000L

    @Test
    fun dumpsAndTempFilesAreAlwaysJunk() {
        for (name in listOf("crash.dmp", "mini.MDMP", "session.tmp", "run.trace.txt", "old.bak", "x.old")) {
            assertTrue(name, GameStorageOptimizer.isJunkName(name, fresh, now))
        }
    }

    @Test
    fun logsAreOnlyJunkWhenStale() {
        assertTrue(GameStorageOptimizer.isJunkName("game.log", stale, now))
        assertFalse(GameStorageOptimizer.isJunkName("game.log", fresh, now))
    }

    @Test
    fun gameDataIsNeverJunk() {
        for (name in listOf("data.pak", "Game.exe", "shader.spv", "config.ini", "sound.ogg")) {
            assertFalse(name, GameStorageOptimizer.isJunkName(name, stale, now))
        }
    }
}
