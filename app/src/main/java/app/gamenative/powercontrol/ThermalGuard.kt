package app.gamenative.powercontrol

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.powercontrol.metrics.JsonlSessionLog
import app.gamenative.powercontrol.metrics.ThermalHeadroom
import app.gamenative.ui.util.SnackbarManager
import timber.log.Timber
import java.io.File
import java.util.Locale

/**
 * Session owner of the heat budget.
 *
 * A container that renders through Wine, DXVK and zink keeps the GPU, the memory controller and
 * two CPU clusters busy at once, and a fanless phone answers that with sustained clocks down and
 * a hot chassis. This loop watches the platform's own thermal signal - [ThermalHeadroom] (both
 * the status and the ten second forecast), the battery temperature reported with
 * ACTION_BATTERY_CHANGED - and, once the device is genuinely warm, publishes an FPS ceiling
 * through [PowerManager.applyThermalCeiling] so the frame limiter gives the heat back.
 *
 * The levels only step up immediately and step down after [COOL_DOWN_CYCLES] cool readings, so a
 * single noisy sample cannot make the frame rate flap. Nothing here changes a user setting: the
 * ceiling is applied to the running engines and released when the device cools.
 */
object ThermalGuard {
    /** How warm the device is, ordered from cool to critical. */
    enum class Level { COOL, WARM, HOT, CRITICAL }

    private const val TAG = "ThermalGuard"
    private const val CYCLE_INTERVAL_MS = 5_000L

    /** Cool cycles needed before the ceiling is released, so a cooled sample must be steady. */
    private const val COOL_DOWN_CYCLES = 4

    private const val HOT_CAP_FPS = 30
    private const val CRITICAL_CAP_FPS = 20

    // android.os.PowerManager thermal status values, copied so this object stays testable
    // without the platform class: 0 NONE, 1 LIGHT, 2 MODERATE, 3 SEVERE, 4 CRITICAL, 5+ EMERGENCY.
    private const val STATUS_NONE = 0
    private const val STATUS_LIGHT = 1
    private const val STATUS_MODERATE = 2

    private const val HEADROOM_WARM = 0.70f
    private const val HEADROOM_HOT = 0.40f
    private const val HEADROOM_CRITICAL = 0.20f

    private const val BATTERY_WARM_C = 39.0f
    private const val BATTERY_HOT_C = 42.0f
    private const val BATTERY_CRITICAL_C = 45.0f

    private val sessionLog = JsonlSessionLog(TAG, "thermal-")

    /** Current heat level; [Level.COOL] until a cycle has run. */
    @Volatile
    var level: Level = Level.COOL
        private set

    /** FPS ceiling this guard currently holds, 0 when the device is cool. */
    @Volatile
    var capFps: Int = 0
        private set

    /** Last battery temperature in degrees Celsius, NaN until one was read. */
    @Volatile
    var batteryCelsius: Float = Float.NaN
        private set

    /** Last thermal headroom forecast, NaN until one was read. */
    @Volatile
    var headroom: Float = Float.NaN
        private set

    /** Last platform thermal status (android.os.PowerManager.THERMAL_STATUS_*), null until read. */
    @Volatile
    var thermalStatus: Int? = null
        private set

    private var thread: Thread? = null
    private var running = false
    private var paused = false
    private var coolerCycles = 0

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var enabled = true

    fun isRunning(): Boolean = running

    /** Starts the loop for a session. A second call while running is ignored. */
    fun start(context: Context, logDirectory: File?) {
        if (running) return

        appContext = context.applicationContext
        enabled = runCatching { PrefManager.thermalGuardEnabled }.getOrDefault(true)
        if (!enabled) {
            Timber.tag(TAG).i("Thermal guard is switched off in the settings")
            return
        }

        level = Level.COOL
        capFps = 0
        coolerCycles = 0
        paused = false
        batteryCelsius = Float.NaN
        headroom = Float.NaN
        thermalStatus = null
        logDirectory?.let { sessionLog.open(it, System.currentTimeMillis()) }

        running = true
        thread = Thread { loop() }.apply {
            name = "ThermalGuard"
            priority = Thread.MIN_PRIORITY
            start()
        }
        Timber.tag(TAG).i("Thermal guard started")
    }

    /** Stops the loop and releases the ceiling. Called when the session ends. */
    fun stop(restoreCap: Boolean = true) {
        val wasRunning = running || thread != null
        running = false
        paused = false
        thread?.interrupt()
        thread?.join(1_000)
        thread = null

        if (restoreCap && capFps > 0) {
            PowerManager.applyThermalCeiling(0)
        }
        capFps = 0
        level = Level.COOL
        coolerCycles = 0

        if (wasRunning) {
            Timber.tag(TAG).i("Thermal guard stopped")
            sessionLog.close()
        }
    }

    /**
     * Stops the loop while the session is in the background but keeps the current level, because
     * a paused session is not a cooled device. [resume] picks the ceiling back up.
     */
    fun pause() {
        if (!running) return
        running = false
        paused = true
        thread?.interrupt()
        thread?.join(1_000)
        thread = null
        Timber.tag(TAG).i("Thermal guard paused at %s (cap=%d)", level, capFps)
    }

    fun resume() {
        if (running || !paused) return
        if (appContext == null) return
        paused = false
        enabled = runCatching { PrefManager.thermalGuardEnabled }.getOrDefault(true)
        if (!enabled) {
            PowerManager.applyThermalCeiling(0)
            capFps = 0
            level = Level.COOL
            return
        }

        running = true
        thread = Thread { loop() }.apply {
            name = "ThermalGuard"
            priority = Thread.MIN_PRIORITY
            start()
        }
        // The adaptive cap restarts with the session; re-assert the ceiling it must respect.
        if (capFps > 0) PowerManager.applyThermalCeiling(capFps)
        Timber.tag(TAG).i("Thermal guard resumed at %s (cap=%d)", level, capFps)
    }

    /**
     * Heat level for one sample. Pure so the thresholds can be tested without a device.
     * The hottest signal wins: the platform status, the headroom forecast and the battery.
     */
    internal fun levelFor(status: Int?, headroom: Float?, batteryCelsius: Float): Level {
        var level = Level.COOL
        fun raise(candidate: Level) {
            if (candidate.ordinal > level.ordinal) level = candidate
        }

        when (status) {
            null, STATUS_NONE -> Unit
            STATUS_LIGHT -> raise(Level.WARM)
            STATUS_MODERATE -> raise(Level.HOT)
            else -> raise(Level.CRITICAL)
        }

        if (headroom != null && headroom.isFinite()) {
            when {
                headroom < HEADROOM_CRITICAL -> raise(Level.CRITICAL)
                headroom < HEADROOM_HOT -> raise(Level.HOT)
                headroom < HEADROOM_WARM -> raise(Level.WARM)
            }
        }

        if (batteryCelsius.isFinite() && batteryCelsius > 0f) {
            when {
                batteryCelsius >= BATTERY_CRITICAL_C -> raise(Level.CRITICAL)
                batteryCelsius >= BATTERY_HOT_C -> raise(Level.HOT)
                batteryCelsius >= BATTERY_WARM_C -> raise(Level.WARM)
            }
        }

        return level
    }

    /** FPS ceiling for a level; 0 means "no ceiling". */
    internal fun capFor(level: Level): Int = when (level) {
        Level.HOT -> HOT_CAP_FPS
        Level.CRITICAL -> CRITICAL_CAP_FPS
        Level.COOL, Level.WARM -> 0
    }

    private fun loop() {
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                runCatching { runCycle() }
                    .onFailure { Timber.tag(TAG).e(it, "Thermal guard cycle failed") }
                Thread.sleep(CYCLE_INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
            Timber.tag(TAG).d("Thermal guard loop interrupted")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Thermal guard loop error")
        }
    }

    private fun runCycle() {
        val context = appContext ?: return

        val status = runCatching { ThermalHeadroom.status(context, publish = false) }.getOrNull()
        val forecast = runCatching { ThermalHeadroom.read(context, publish = false) }.getOrNull()
        val battery = batteryTemperature(context)

        thermalStatus = status
        if (forecast != null) headroom = forecast
        if (battery.isFinite()) batteryCelsius = battery

        val observed = levelFor(status, forecast, battery)
        val next = decide(observed)
        if (next != level || capFps != capFor(next)) {
            applyLevel(next)
        }

        Timber.tag(TAG).d(
            "status=%s headroom=%s battery=%s level=%s cap=%d",
            status?.toString() ?: "?",
            if (forecast != null) String.format(Locale.US, "%.2f", forecast) else "?",
            if (battery.isFinite()) String.format(Locale.US, "%.1fC", battery) else "?",
            level,
            capFps,
        )
    }

    /** Immediate on the way up, [COOL_DOWN_CYCLES] steady cool readings on the way down. */
    private fun decide(observed: Level): Level {
        if (observed.ordinal > level.ordinal) {
            coolerCycles = 0
            return observed
        }
        if (observed.ordinal < level.ordinal) {
            coolerCycles += 1
            if (coolerCycles >= COOL_DOWN_CYCLES) {
                coolerCycles = 0
                return observed
            }
            return level
        }
        coolerCycles = 0
        return level
    }

    private fun applyLevel(next: Level) {
        val previous = level
        val cap = capFor(next)
        level = next
        capFps = cap

        PowerManager.applyThermalCeiling(cap)
        logTransition(previous, next, cap)

        val tighten = next.ordinal > previous.ordinal
        val released = next == Level.COOL && previous != Level.COOL
        if (tighten || released) notifyLevel(next, cap)
    }

    private fun logTransition(previous: Level, next: Level, cap: Int) {
        val line = String.format(
            Locale.US,
            "{\"t\":%d,\"event\":\"level\",\"from\":\"%s\",\"to\":\"%s\",\"cap\":%d," +
                "\"headroom\":%s,\"batteryC\":%s,\"thermalStatus\":%s}",
            System.currentTimeMillis(),
            previous,
            next,
            cap,
            if (headroom.isFinite()) String.format(Locale.US, "%.3f", headroom) else "null",
            if (batteryCelsius.isFinite()) String.format(Locale.US, "%.1f", batteryCelsius) else "null",
            thermalStatus?.toString() ?: "null",
        )
        sessionLog.append(line)
        Timber.tag(TAG).i("%s -> %s, cap=%d", previous, next, cap)
    }

    /** A snackbar, but only while the session is in front of the user. */
    private fun notifyLevel(level: Level, cap: Int) {
        val context = appContext ?: return
        if (!PluviaApp.isActivityInForeground) return

        val message = when (level) {
            Level.CRITICAL -> context.getString(R.string.thermal_guard_critical, cap)
            Level.HOT -> context.getString(R.string.thermal_guard_hot, cap)
            Level.COOL -> context.getString(R.string.thermal_guard_recovered)
            Level.WARM -> null
        } ?: return

        Handler(Looper.getMainLooper()).post {
            runCatching { SnackbarManager.show(message) }
        }
    }

    /** Battery temperature from the sticky battery broadcast, NaN when it is not reported. */
    internal fun batteryTemperature(context: Context): Float {
        val intent: Intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return Float.NaN

        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return Float.NaN
        return tenths / 10f
    }
}
