package app.gamenative.powercontrol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The heat levels decide when the frame cap is tightened, so they must follow the hottest
 * signal and never invent one: a missing reading is "no information", not "cool".
 */
class ThermalGuardTest {

    @Test
    fun noReadingIsCool() {
        assertEquals(ThermalGuard.Level.COOL, ThermalGuard.levelFor(null, null, Float.NaN))
        assertEquals(ThermalGuard.Level.COOL, ThermalGuard.levelFor(null, Float.NaN, 0f))
    }

    @Test
    fun platformStatusMapsToLevels() {
        assertEquals(ThermalGuard.Level.COOL, ThermalGuard.levelFor(0, null, Float.NaN))
        assertEquals(ThermalGuard.Level.WARM, ThermalGuard.levelFor(1, null, Float.NaN))
        assertEquals(ThermalGuard.Level.HOT, ThermalGuard.levelFor(2, null, Float.NaN))
        assertEquals(ThermalGuard.Level.CRITICAL, ThermalGuard.levelFor(3, null, Float.NaN))
        assertEquals(ThermalGuard.Level.CRITICAL, ThermalGuard.levelFor(4, null, Float.NaN))
        assertEquals(ThermalGuard.Level.CRITICAL, ThermalGuard.levelFor(6, null, Float.NaN))
    }

    @Test
    fun headroomForecastsTheHeat() {
        assertEquals(ThermalGuard.Level.COOL, ThermalGuard.levelFor(0, 0.95f, Float.NaN))
        assertEquals(ThermalGuard.Level.WARM, ThermalGuard.levelFor(0, 0.60f, Float.NaN))
        assertEquals(ThermalGuard.Level.HOT, ThermalGuard.levelFor(0, 0.30f, Float.NaN))
        assertEquals(ThermalGuard.Level.CRITICAL, ThermalGuard.levelFor(0, 0.10f, Float.NaN))
    }

    @Test
    fun batteryTemperatureMapsToLevels() {
        assertEquals(ThermalGuard.Level.COOL, ThermalGuard.levelFor(0, null, 35.0f))
        assertEquals(ThermalGuard.Level.WARM, ThermalGuard.levelFor(0, null, 39.5f))
        assertEquals(ThermalGuard.Level.HOT, ThermalGuard.levelFor(0, null, 43.0f))
        assertEquals(ThermalGuard.Level.CRITICAL, ThermalGuard.levelFor(0, null, 46.0f))
    }

    @Test
    fun hottestSignalWins() {
        // A moderate platform status with a cold battery and a calm forecast is still HOT.
        assertEquals(ThermalGuard.Level.HOT, ThermalGuard.levelFor(2, 0.9f, 30.0f))
        // A cool status with a red-hot battery escalates.
        assertEquals(ThermalGuard.Level.CRITICAL, ThermalGuard.levelFor(0, 0.9f, 47.0f))
    }

    @Test
    fun capsOnlyApplyFromHot() {
        assertEquals(0, ThermalGuard.capFor(ThermalGuard.Level.COOL))
        assertEquals(0, ThermalGuard.capFor(ThermalGuard.Level.WARM))
        assertEquals(30, ThermalGuard.capFor(ThermalGuard.Level.HOT))
        assertEquals(20, ThermalGuard.capFor(ThermalGuard.Level.CRITICAL))
    }
}
