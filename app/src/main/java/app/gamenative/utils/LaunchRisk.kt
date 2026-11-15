package app.gamenative.utils

import android.content.Context
import android.os.StatFs
import app.gamenative.powercontrol.ThermalGuard
import app.gamenative.powercontrol.metrics.ThermalHeadroom
import com.winlator.container.Container
import com.winlator.core.KeyValueSet
import java.util.Locale

/**
 * What could go wrong with the configuration the user is about to launch, and how bad it is.
 *
 * This is advice, not a gate: every combination stays launchable. The point is that the two
 * failure modes that hurt - the app being killed for memory and the device rebooting under a
 * driver the GPU cannot run - are worth one dialog before the session starts, not a surprise
 * after it.
 */
enum class LaunchRiskCode(val rebootRisk: Boolean) {
    /** An Adreno-only driver on a GPU that cannot load it: the kernel driver can take the device down. */
    GPU_DRIVER_MISMATCH(true),

    /** Direct3D 11 without DXVK: the game starts and then reports a missing feature level. */
    NO_DXVK(false),

    /**
     * An arm64ec Wine with an x86_64 (or absent) DXVK: the arm64ec loader cannot load those DLLs,
     * so Direct3D 11 falls back to Wine's builtin and the game reports a missing GPU.
     */
    DXVK_WRONG_ARCH(false),

    /** The driver is asked for more memory than the device can hand out. */
    VRAM_OVER_COMMIT(false),

    /** Little free RAM: the game is the first thing the low-memory killer takes. */
    LOW_FREE_RAM(false),

    /** Little free storage: wineserver and the game both need scratch space. */
    LOW_FREE_STORAGE(false),

    /** The device is already hot: a session now makes a thermal shutdown or reboot likely. */
    DEVICE_HOT(true),
}

/** What the pre-launch dialog was answered with. */
enum class PreLaunchChoice {
    /** Apply the repairs the guard can do, then launch. */
    REPAIR,

    /** Launch exactly as configured. */
    PROCEED,

    /** Do not launch. */
    ABORT,
}

data class LaunchRisk(
    val code: LaunchRiskCode,
    val detail: String,
) {
    val isRebootRisk: Boolean get() = code.rebootRisk
}

/** Assesses a container against the device it will run on. Pure apart from the device reads. */
object LaunchRiskAssessor {
    private const val LOW_RAM_MB = 900
    private const val LOW_STORAGE_MB = 1_500L

    fun assess(context: Context, container: Container, gpu: GpuCompatReport): List<LaunchRisk> {
        val risks = mutableListOf<LaunchRisk>()

        gpu.blockers.forEach { issue ->
            when (issue.code) {
                GpuIssueCode.BIONIC_WRAPPER_ON_NON_ADRENO,
                GpuIssueCode.ADRENO_ONLY_DRIVER,
                -> risks += LaunchRisk(LaunchRiskCode.GPU_DRIVER_MISMATCH, issue.detail)
                else -> Unit
            }
        }
        // A wrapper on an unknown GPU is not in `blockers`; still worth saying out loud.
        if (risks.none { it.code == LaunchRiskCode.GPU_DRIVER_MISMATCH } &&
            gpu.family.isKnownNonAdreno &&
            GpuCompat.isAdrenoOnlyDriver(container.graphicsDriver)
        ) {
            risks += LaunchRisk(
                LaunchRiskCode.GPU_DRIVER_MISMATCH,
                "${container.graphicsDriver} is an Adreno-only driver on ${gpu.family.displayName()}",
            )
        }

        // Only what can end the session: a missing DXVK makes a game complain, not crash, and
        // this dialog is for the two endings worth warning about - a kill and a reboot.
        gpu.warnings.forEach { issue ->
            if (issue.code == GpuIssueCode.VRAM_OVER_COMMIT) {
                risks += LaunchRisk(LaunchRiskCode.VRAM_OVER_COMMIT, issue.detail)
            }
        }

        // arm64ec Wine needs arm64ec Direct3D DLLs. Anything else is a session that starts and
        // then fails to create a D3D11 device.
        if (container.wineVersion.orEmpty().contains("arm64ec", ignoreCase = true)) {
            val wrapper = container.dxWrapper.orEmpty().lowercase(Locale.US)
            val version = KeyValueSet(container.dxWrapperConfig).get("version")
            if (!wrapper.contains("dxvk")) {
                risks += LaunchRisk(
                    LaunchRiskCode.DXVK_WRONG_ARCH,
                    "arm64ec Wine with dxwrapper '$wrapper': Direct3D 11 needs DXVK " +
                        ExtraComponents.RECOMMENDED_ARM64EC_DXVK,
                )
            } else if (!ExtraComponents.isArm64ecComponent(version)) {
                risks += LaunchRisk(
                    LaunchRiskCode.DXVK_WRONG_ARCH,
                    "DXVK '${version.ifEmpty { "default" }}' is not an arm64ec build",
                )
            }
        }

        val availableMb = availableRamMb(context)
        val requestedMb = KeyValueSet(container.graphicsDriverConfig)
            .get("maxDeviceMemory", "0").toIntOrNull() ?: 0
        if (availableMb in 1 until LOW_RAM_MB || (requestedMb > 0 && availableMb in 1 until requestedMb)) {
            risks += LaunchRisk(
                LaunchRiskCode.LOW_FREE_RAM,
                "free RAM ${availableMb}MB, driver asks for ${requestedMb}MB",
            )
        }

        val freeMb = freeStorageMb(context)
        if (freeMb in 1 until LOW_STORAGE_MB) {
            risks += LaunchRisk(LaunchRiskCode.LOW_FREE_STORAGE, "only ${freeMb}MB free")
        }

        // Read the platform directly: the session (and its ThermalGuard) has not started yet.
        val thermalStatus = runCatching { ThermalHeadroom.status(context, publish = false) }.getOrNull()
        val batteryCelsius = runCatching { ThermalGuard.batteryTemperature(context) }.getOrNull() ?: Float.NaN
        val level = ThermalGuard.levelFor(thermalStatus, null, batteryCelsius)
        if (level == ThermalGuard.Level.CRITICAL) {
            risks += LaunchRisk(
                LaunchRiskCode.DEVICE_HOT,
                String.format(Locale.US, "battery %.1fC, thermal level %s", batteryCelsius, level),
            )
        }

        return risks
    }

    private fun availableRamMb(context: Context): Int = MemoryBudget.freeRamMb(context)

    private fun freeStorageMb(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes / (1024L * 1024L)
    }.getOrDefault(0L)
}
