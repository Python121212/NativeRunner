package app.gamenative.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.winlator.container.Container
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import timber.log.Timber
import java.util.Locale

/**
 * The GPU families this app's driver stack treats differently.
 *
 * Only Adreno has open user-space Vulkan drivers (Turnip, the AdrenoTools wrapper package) and
 * only Adreno has the kgsl device nodes those drivers open. Mali, Xclipse and PowerVR run the
 * vendor driver through the system loader and cannot take an Adreno driver at all: loading one
 * does not fail cleanly, it wedges the kernel driver and can take the whole device down.
 */
enum class GpuFamily {
    ADRENO,
    MALI,
    XCLIPSE,
    POWERVR,
    UNKNOWN,
    ;

    val isAdreno: Boolean get() = this == ADRENO

    /**
     * True when the GPU is known well enough to refuse an Adreno-only driver. [UNKNOWN] never
     * blocks: a renderer string this app cannot parse must not turn a working device into one
     * that refuses to launch.
     */
    val isKnownNonAdreno: Boolean get() = this == MALI || this == XCLIPSE || this == POWERVR

    fun displayName(): String = when (this) {
        ADRENO -> "Adreno"
        MALI -> "Mali"
        XCLIPSE -> "Xclipse"
        POWERVR -> "PowerVR"
        UNKNOWN -> "GPU"
    }
}

/** What [GpuCompat.evaluate] found, independent of any resource id so it stays unit testable. */
enum class GpuIssueCode(val blocker: Boolean) {
    /** A glibc driver id that only exists on Adreno (turnip, adreno, sd-8-elite). */
    ADRENO_ONLY_DRIVER(blocker = true),

    /** A Bionic wrapper driver (`wrapper*`): an AdrenoTools package built for Adreno. */
    BIONIC_WRAPPER_ON_NON_ADRENO(blocker = true),

    /** No DXVK and no VKD3D: Direct3D 11 falls back to wined3d and reports feature level 11_0. */
    D3D11_WITHOUT_DXVK(blocker = false),

    /** Turnip-only env vars left over on hardware that has no Turnip. */
    TURNIP_ENV_ON_NON_ADRENO(blocker = false),

    /** maxDeviceMemory asks the driver for more memory than the device can give it. */
    VRAM_OVER_COMMIT(blocker = false),
}

data class GpuCompatIssue(val code: GpuIssueCode, val detail: String)

data class GpuCompatReport(
    val family: GpuFamily,
    val renderer: String?,
    val issues: List<GpuCompatIssue>,
) {
    val blockers: List<GpuCompatIssue> get() = issues.filter { it.code.blocker }
    val warnings: List<GpuCompatIssue> get() = issues.filterNot { it.code.blocker }
    val hasBlockingIssue: Boolean get() = issues.any { it.code.blocker }
}

/**
 * Keeps a container's graphics driver and environment in line with the GPU it will run on.
 *
 * The rules are deliberately conservative: only a driver that provably cannot run on the device
 * is refused or rewritten, everything else is logged as a warning so a working setup is never
 * taken away. Adreno behaviour is unchanged.
 */
object GpuCompat {
    private const val TAG = "GpuCompat"

    /** The universal glibc driver: Mesa zink over whatever Vulkan driver the device ships. */
    const val UNIVERSAL_DRIVER = "vortek"

    /** Driver ids whose binaries only exist for Adreno (freedreno / kgsl). */
    private val ADRENO_ONLY_DRIVER_IDS = arrayOf("turnip", "adreno", "sd-8-elite")

    private const val BIONIC_WRAPPER_PREFIX = "wrapper"

    /** The wrapper version that means "the device's own Vulkan driver", not a shipped package. */
    private const val SYSTEM_WRAPPER_VERSION = "system"

    /** Env vars that only do something under the Turnip (freedreno) driver. */
    private val TURNIP_ONLY_ENV_KEYS = arrayOf("TU_DEBUG", "TU_OVERRIDE_HEAP_SIZE")

    /**
     * Env vars an AdrenoTools wrapper recipe leaves behind. On every other GPU they only get in the
     * way: `WRAPPER_MAX_IMAGE_COUNT=0` makes the wrapper rebuild each image every frame, and
     * `MESA_VK_WSI_PRESENT_MODE` pins a present mode the launch already derives from the container's
     * `presentMode` setting, so a stale copy overrides the user's choice.
     */
    private val ADRENO_WRAPPER_ENV_KEYS = arrayOf("WRAPPER_MAX_IMAGE_COUNT", "MESA_VK_WSI_PRESENT_MODE")

    /**
     * Hardware names of Google's Tensor devices. Every Pixel since the 6 ships a Mali GPU, and the
     * renderer string is empty until the GPU probe has run, so the name is all there is to go on at
     * that point. Reporting [GpuFamily.UNKNOWN] instead would let an Adreno-only driver through on
     * a device that cannot load it.
     */
    private val TENSOR_HARDWARE = arrayOf(
        "tensor", "tegu", "tokay", "caiman", "komodo", "shiba", "husky", "akita",
        "panther", "cheetah", "lynx", "oriole", "raven", "bluejay", "felix", "tangorpro",
    )

    /** Above this share of physical RAM a driver allocation request is treated as over-commit. */
    private const val VRAM_BUDGET_RATIO = 0.40f

    /**
     * GPU family from a GL renderer string, with Build.HARDWARE as a fallback because some ROMs
     * report an empty renderer before the GPU probe has run.
     */
    fun familyOf(renderer: String?, hardware: String? = null): GpuFamily {
        val r = renderer.orEmpty().lowercase(Locale.US)
        if (r.contains("xclipse")) return GpuFamily.XCLIPSE
        if (r.contains("adreno")) return GpuFamily.ADRENO
        if (r.contains("mali") || r.contains("immortalis")) return GpuFamily.MALI
        if (r.contains("powervr")) return GpuFamily.POWERVR

        val h = hardware.orEmpty().lowercase(Locale.US)
        return when {
            h.contains("mali") -> GpuFamily.MALI
            h.contains("tensor") -> GpuFamily.MALI
            TENSOR_HARDWARE.any { it == h } -> GpuFamily.MALI
            else -> GpuFamily.UNKNOWN
        }
    }

    /**
     * True for a driver id whose binaries are built only for Adreno hardware.
     *
     * A Bionic `Wrapper` is not automatically one of those: with the built-in System driver
     * ([SYSTEM_WRAPPER_VERSION], the default) it is the shared wrapper on top of whatever Vulkan
     * driver the device ships, which loads on Mali and Xclipse just as well. Only a named package
     * version is an AdrenoTools build, and only that one is Adreno-only.
     */
    fun isAdrenoOnlyDriver(graphicsDriver: String?, wrapperVersion: String? = null): Boolean {
        val driver = graphicsDriver.orEmpty().trim().lowercase(Locale.US)
        if (driver.isEmpty()) return false
        if (driver.startsWith(BIONIC_WRAPPER_PREFIX)) {
            return !wrapperVersion.orEmpty().trim().equals(SYSTEM_WRAPPER_VERSION, ignoreCase = true)
        }
        return ADRENO_ONLY_DRIVER_IDS.any { it == driver }
    }

    /** True for the Bionic `wrapper*` drivers, which are shipped as AdrenoTools packages. */
    fun isBionicWrapperDriver(graphicsDriver: String?): Boolean =
        graphicsDriver.orEmpty().trim().lowercase(Locale.US).startsWith(BIONIC_WRAPPER_PREFIX)

    /** The wrapper version in the container's driver config, or null when it is not set. */
    fun wrapperVersionOf(container: Container): String? =
        KeyValueSet(container.graphicsDriverConfig).get("version").takeIf { it.isNotBlank() }

    /**
     * Issues for one container configuration. Pure: the caller supplies everything the GPU probe
     * would have read, so the rules can be tested without a device.
     */
    fun evaluate(
        family: GpuFamily,
        containerVariant: String?,
        graphicsDriver: String?,
        dxWrapper: String?,
        envVarsText: String?,
        maxDeviceMemoryMb: Int,
        totalRamMb: Int,
        graphicsDriverVersion: String? = null,
    ): List<GpuCompatIssue> {
        val issues = mutableListOf<GpuCompatIssue>()
        val driver = graphicsDriver.orEmpty().trim()
        val bionic = containerVariant.orEmpty().equals(Container.BIONIC, ignoreCase = true)

        if (family.isKnownNonAdreno && isAdrenoOnlyDriver(driver, graphicsDriverVersion)) {
            if (isBionicWrapperDriver(driver)) {
                issues += GpuCompatIssue(
                    GpuIssueCode.BIONIC_WRAPPER_ON_NON_ADRENO,
                    "${family.displayName()} cannot load the AdrenoTools package '$driver'",
                )
            } else if (!bionic) {
                // A glibc container can be moved to the universal driver; a Bionic one has no
                // alternative, so it is reported through the wrapper branch above.
                issues += GpuCompatIssue(
                    GpuIssueCode.ADRENO_ONLY_DRIVER,
                    "'$driver' is an Adreno-only driver on ${family.displayName()}",
                )
            } else {
                issues += GpuCompatIssue(
                    GpuIssueCode.BIONIC_WRAPPER_ON_NON_ADRENO,
                    "Bionic container with '$driver' on ${family.displayName()}",
                )
            }
        }

        val wrapper = dxWrapper.orEmpty().lowercase(Locale.US)
        val hasDxvk = wrapper.contains("dxvk")
        val hasVkd3d = wrapper.contains("vkd3d")
        if (!hasDxvk) {
            issues += GpuCompatIssue(
                GpuIssueCode.D3D11_WITHOUT_DXVK,
                if (hasVkd3d) {
                    "dxwrapper '$wrapper' installs D3D12 only; Direct3D 11 falls back to wined3d"
                } else {
                    "no DXVK: Direct3D 11 falls back to wined3d"
                },
            )
        }

        if (family.isKnownNonAdreno) {
            val leftover = turnipEnvKeys(envVarsText)
            if (leftover.isNotEmpty()) {
                issues += GpuCompatIssue(
                    GpuIssueCode.TURNIP_ENV_ON_NON_ADRENO,
                    "Turnip-only env vars on ${family.displayName()}: ${leftover.joinToString(", ")}",
                )
            }
        }

        if (maxDeviceMemoryMb > 0 && totalRamMb > 0 &&
            maxDeviceMemoryMb > (totalRamMb * VRAM_BUDGET_RATIO).toInt()
        ) {
            issues += GpuCompatIssue(
                GpuIssueCode.VRAM_OVER_COMMIT,
                "maxDeviceMemory=${maxDeviceMemoryMb}MB is above the ${(totalRamMb * VRAM_BUDGET_RATIO).toInt()}MB " +
                    "budget of a ${totalRamMb}MB device",
            )
        }

        return issues
    }

    /** Turnip-only env keys present in [envVarsText]; empty on Adreno or with nothing to strip. */
    fun turnipEnvKeys(envVarsText: String?): List<String> {
        if (envVarsText.isNullOrBlank()) return emptyList()
        val env = EnvVars(envVarsText)
        return TURNIP_ONLY_ENV_KEYS.filter { env.has(it) }
    }

    /**
     * [envVarsText] without the env vars that belong to a driver this device does not have.
     * Zink/Mesa knobs are kept: they are meaningful under Vortek and VirGL too.
     */
    fun sanitizedEnvVars(envVarsText: String?, family: GpuFamily): Pair<String, List<String>> {
        val text = envVarsText.orEmpty()
        if (text.isBlank() || family.isAdreno) return text to emptyList()

        val env = EnvVars(text)
        val removed = mutableListOf<String>()
        for (key in TURNIP_ONLY_ENV_KEYS) {
            if (env.has(key)) {
                env.remove(key)
                removed += key
            }
        }
        return if (removed.isEmpty()) text to emptyList() else env.toString() to removed
    }

    /**
     * [envVarsText] without the knobs an AdrenoTools wrapper recipe leaves behind. Only the values
     * that only make sense on Adreno are dropped; everything the user may have tuned by hand stays.
     */
    fun sanitizedWrapperEnvVars(envVarsText: String?): Pair<String, List<String>> {
        val text = envVarsText.orEmpty()
        if (text.isBlank()) return text to emptyList()

        val env = EnvVars(text)
        val removed = mutableListOf<String>()
        for (key in ADRENO_WRAPPER_ENV_KEYS) {
            if (!env.has(key)) continue
            // An image count of 0 means "recreate every image, every frame", which is the wrong
            // default anywhere else; any other value is a deliberate choice and is kept.
            if (key == "WRAPPER_MAX_IMAGE_COUNT" && env.get(key) != "0") continue
            env.remove(key)
            removed += key
        }
        return if (removed.isEmpty()) text to emptyList() else env.toString() to removed
    }

    /**
     * Drops the env vars this device cannot use, straight into the container. Called before a launch
     * so a recipe written for another GPU cannot quietly break presentation here.
     *
     * @return the keys that were removed, empty when the container was already clean
     */
    fun sanitizeEnvForDevice(container: Container, family: GpuFamily): List<String> {
        if (family.isAdreno) return emptyList()

        val changed = mutableListOf<String>()
        val (withoutTurnip, turnipRemoved) = sanitizedEnvVars(container.envVars, family)
        val (withoutWrapper, wrapperRemoved) = sanitizedWrapperEnvVars(withoutTurnip)
        changed += turnipRemoved
        changed += wrapperRemoved
        if (changed.isEmpty()) return emptyList()

        container.envVars = withoutWrapper
        runCatching { container.saveData() }
            .onFailure { Timber.tag(TAG).w(it, "Could not save the sanitized env vars") }
        Timber.tag(TAG).i(
            "Dropped env vars %s cannot use: %s",
            family.displayName(),
            changed.joinToString(", "),
        )
        return changed
    }

    /** GL renderer of the device, or null when the probe has nothing to say. */
    fun rendererOf(context: Context?): String? {
        if (context == null) return null
        return runCatching { GPUInformation.getRenderer(context) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** GPU family of the device this process runs on. */
    fun detectFamily(context: Context?): GpuFamily =
        familyOf(rendererOf(context), Build.HARDWARE)

    fun totalRamMb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            am.getMemoryInfo(info)
            (info.totalMem / (1024L * 1024L)).toInt()
        }.getOrDefault(0)
    }

    /** Reads the container, evaluates it and writes the findings to the session log. */
    fun evaluateContainer(context: Context, container: Container): GpuCompatReport {
        val renderer = rendererOf(context)
        val family = familyOf(renderer, Build.HARDWARE)
        val driverConfig = KeyValueSet(container.graphicsDriverConfig)
        val maxDeviceMemoryMb = driverConfig.get("maxDeviceMemory", "0").toIntOrNull() ?: 0

        val report = GpuCompatReport(
            family = family,
            renderer = renderer,
            issues = evaluate(
                family = family,
                containerVariant = container.containerVariant,
                graphicsDriver = container.graphicsDriver,
                dxWrapper = container.dxWrapper,
                envVarsText = container.envVars,
                maxDeviceMemoryMb = maxDeviceMemoryMb,
                totalRamMb = totalRamMb(context),
                graphicsDriverVersion = driverConfig.get("version"),
            ),
        )

        Timber.tag(TAG).i(
            "GPU=%s renderer=%s variant=%s driver=%s dxwrapper=%s issues=%d blockers=%d",
            family,
            renderer ?: "?",
            container.containerVariant,
            container.graphicsDriver,
            container.dxWrapper,
            report.issues.size,
            report.blockers.size,
        )
        report.issues.forEach { issue ->
            if (issue.code.blocker) {
                Timber.tag(TAG).e("%s: %s", issue.code, issue.detail)
            } else {
                Timber.tag(TAG).w("%s: %s", issue.code, issue.detail)
            }
        }
        return report
    }

    /**
     * Applies the repairs that cannot go wrong: an Adreno-only driver in a glibc container is
     * moved to the universal one, and Turnip-only env vars are dropped on hardware that has no
     * Turnip. Anything that would change what the user asked for (a Bionic container) is left
     * alone for [blockingMessage] to explain.
     *
     * @return human readable list of what changed, empty when nothing did
     */
    fun repairContainer(
        container: Container,
        report: GpuCompatReport,
        memoryBudgetMb: Int = 0,
    ): List<String> {
        val changes = mutableListOf<String>()

        val bionic = container.containerVariant.orEmpty().equals(Container.BIONIC, ignoreCase = true)
        if (report.family.isKnownNonAdreno &&
            !bionic &&
            isAdrenoOnlyDriver(container.graphicsDriver, wrapperVersionOf(container)) &&
            !isBionicWrapperDriver(container.graphicsDriver) &&
            container.graphicsDriver != UNIVERSAL_DRIVER
        ) {
            val previous = container.graphicsDriver
            container.graphicsDriver = UNIVERSAL_DRIVER
            container.graphicsDriverVersion = ""
            changes += "$previous -> $UNIVERSAL_DRIVER"
            Timber.tag(TAG).i(
                "%s has no %s driver; switched the glibc container to %s",
                report.family,
                previous,
                UNIVERSAL_DRIVER,
            )
        }

        val (env, removedEnv) = sanitizedEnvVars(container.envVars, report.family)
        if (removedEnv.isNotEmpty()) {
            container.envVars = env
            changes += "env -" + removedEnv.joinToString(",")
            Timber.tag(TAG).i("Removed non-Adreno env vars: %s", removedEnv.joinToString(", "))
        }

        // arm64ec Wine cannot load an x86_64 DXVK, and a container that is not on DXVK at all has
        // no chance at Direct3D 11. Both are fixed by pointing the container at an arm64ec build.
        if (container.wineVersion.orEmpty().contains("arm64ec", ignoreCase = true)) {
            val wrapper = container.dxWrapper.orEmpty()
            if (!wrapper.lowercase(Locale.US).contains("dxvk")) {
                container.dxWrapper = "dxvk"
                changes += "dxwrapper ${wrapper.ifEmpty { "(none)" }} -> dxvk"
                Timber.tag(TAG).i("arm64ec Wine: dxwrapper '%s' -> dxvk", wrapper)
            }
            val dxConfig = KeyValueSet(container.dxWrapperConfig)
            val version = dxConfig.get("version")
            if (!ExtraComponents.isArm64ecComponent(version)) {
                val replacement = ExtraComponents.RECOMMENDED_ARM64EC_DXVK
                dxConfig.put("version", replacement)
                container.dxWrapperConfig = dxConfig.toString()
                changes += "dxvk ${version.ifEmpty { "(default)" }} -> $replacement"
                Timber.tag(TAG).i("arm64ec Wine: DXVK %s -> %s", version, replacement)
            }
        }

        // A 4 GB video memory request on a phone that has 2 GB free is what turns a slow session
        // into a session the low-memory killer ends. Bring the request inside what is actually
        // available instead of letting the driver find out the hard way.
        if (memoryBudgetMb > 0) {
            val driverConfig = KeyValueSet(container.graphicsDriverConfig)
            val configured = driverConfig.get("maxDeviceMemory", "0").toIntOrNull() ?: 0
            if (configured > memoryBudgetMb) {
                driverConfig.put("maxDeviceMemory", memoryBudgetMb.toString())
                container.graphicsDriverConfig = driverConfig.toString()
                changes += "maxDeviceMemory $configured -> $memoryBudgetMb MB"
                Timber.tag(TAG).i(
                    "Clamped the video memory request from %dMB to the %dMB budget",
                    configured,
                    memoryBudgetMb,
                )
            }
        }

        return changes
    }

    /** True when the guard rewrote the container, so the caller can persist it. */
    fun needsSave(changes: List<String>): Boolean = changes.isNotEmpty()

    /**
     * True when [repairContainer] would actually change this container, so the pre-launch dialog
     * only offers the repair when there is one.
     */
    fun canRepair(
        container: Container,
        report: GpuCompatReport,
        memoryBudgetMb: Int = 0,
    ): Boolean {
        val bionic = container.containerVariant.orEmpty().equals(Container.BIONIC, ignoreCase = true)
        val wouldSwitchDriver = report.family.isKnownNonAdreno &&
            !bionic &&
            isAdrenoOnlyDriver(container.graphicsDriver, wrapperVersionOf(container)) &&
            !isBionicWrapperDriver(container.graphicsDriver) &&
            container.graphicsDriver != UNIVERSAL_DRIVER
        val wouldStripEnv = !report.family.isAdreno &&
            (turnipEnvKeys(container.envVars).isNotEmpty() ||
                sanitizedWrapperEnvVars(container.envVars).second.isNotEmpty())
        val configuredVram = KeyValueSet(container.graphicsDriverConfig)
            .get("maxDeviceMemory", "0").toIntOrNull() ?: 0
        val wouldClampVram = memoryBudgetMb > 0 && configuredVram > memoryBudgetMb
        val wouldFixArm64ecDxvk = container.wineVersion.orEmpty().contains("arm64ec", ignoreCase = true) &&
            run {
                val wrapper = container.dxWrapper.orEmpty().lowercase(Locale.US)
                !wrapper.contains("dxvk") ||
                    !ExtraComponents.isArm64ecComponent(KeyValueSet(container.dxWrapperConfig).get("version"))
            }
        return wouldSwitchDriver || wouldStripEnv || wouldClampVram || wouldFixArm64ecDxvk
    }

    /**
     * User-facing explanation of the first blocking issue, or an empty string when the launch is
     * allowed. Kept next to the rules so the advice and the check cannot drift apart.
     */
    fun blockingMessage(context: Context, report: GpuCompatReport, graphicsDriver: String?): String {
        val family = report.family.displayName()
        val driver = graphicsDriver.orEmpty()
        return when (report.blockers.firstOrNull()?.code) {
            GpuIssueCode.BIONIC_WRAPPER_ON_NON_ADRENO ->
                context.getString(app.gamenative.R.string.gpu_compat_blocker_wrapper, family, driver)
            GpuIssueCode.ADRENO_ONLY_DRIVER ->
                context.getString(app.gamenative.R.string.gpu_compat_blocker_driver, family, driver)
            else -> ""
        }
    }
}
