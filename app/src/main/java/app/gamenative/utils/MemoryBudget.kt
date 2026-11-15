package app.gamenative.utils

import android.app.ActivityManager
import android.content.Context
import timber.log.Timber

/**
 * How much video memory this device can honestly give a Direct3D wrapper.
 *
 * A fixed `maxDeviceMemory` (4096 MB is the value the shipped configs carry) is a promise the
 * device cannot keep on an 8 GB phone that is already holding a running game: DXVK allocates up
 * to it, the low-memory killer sees the pressure, and the session ends on its own. The budget is
 * therefore read from the free RAM at the moment it is needed, and every consumer - the container
 * config, the `dxvk.conf` written at launch, the pre-launch check - uses the same number.
 */
object MemoryBudget {
    /** A share of the free RAM, not the total: the game, wine and the driver live in the same pot. */
    private const val FREE_RAM_SHARE = 0.40

    /** Above this a phone GPU cannot make use of the memory anyway. */
    private const val HARD_CEILING_MB = 3072

    /** Below this the value stops being a budget and starts being a handicap. */
    private const val MIN_USEFUL_MB = 256

    /** Free system RAM in MB, 0 when the platform will not say. */
    @JvmStatic
    fun freeRamMb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            am.getMemoryInfo(info)
            (info.availMem / (1024L * 1024L)).toInt()
        }.getOrDefault(0)
    }

    /** Total system RAM in MB, 0 when the platform will not say. */
    @JvmStatic
    fun totalRamMb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            am.getMemoryInfo(info)
            (info.totalMem / (1024L * 1024L)).toInt()
        }.getOrDefault(0)
    }

    /**
     * The largest `maxDeviceMemory` worth asking for right now, or 0 when the device will not
     * say how much is free (in which case nothing is clamped).
     */
    @JvmStatic
    fun vramBudgetMb(context: Context): Int {
        val free = freeRamMb(context)
        if (free <= 0) return 0
        val share = (free * FREE_RAM_SHARE).toInt().coerceAtMost(HARD_CEILING_MB)
        return if (share < MIN_USEFUL_MB) 0 else share
    }

    /**
     * [configuredMb] brought inside the budget. Returns the input unchanged when there is nothing
     * to clamp, so callers can compare to know whether anything happened.
     */
    @JvmStatic
    fun clamp(context: Context, configuredMb: Int): Int {
        if (configuredMb <= 0) return configuredMb
        val budget = vramBudgetMb(context)
        if (budget <= 0 || configuredMb <= budget) return configuredMb
        Timber.tag("MemoryBudget").i(
            "maxDeviceMemory %dMB does not fit in the %dMB budget of a device with %dMB free",
            configuredMb,
            budget,
            freeRamMb(context),
        )
        return budget
    }
}
