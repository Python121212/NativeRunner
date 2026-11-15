package app.gamenative.utils

import com.winlator.container.Container
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GPU guard has to be conservative: it may refuse a launch only when the driver provably
 * cannot run, and it must leave every Adreno device exactly as it was.
 */
class GpuCompatTest {

    private fun codes(vararg issues: GpuCompatIssue) = issues.map { it.code }.toSet()

    private fun evaluate(
        family: GpuFamily,
        driver: String = "dxvk",
        dxWrapper: String = "dxvk",
        variant: String? = Container.GLIBC,
        envVars: String? = null,
        maxDeviceMemoryMb: Int = 0,
        totalRamMb: Int = 8_000,
        driverVersion: String? = null,
    ) = GpuCompat.evaluate(
        family = family,
        containerVariant = variant,
        graphicsDriver = driver,
        dxWrapper = dxWrapper,
        envVarsText = envVars,
        maxDeviceMemoryMb = maxDeviceMemoryMb,
        totalRamMb = totalRamMb,
        graphicsDriverVersion = driverVersion,
    )

    @Test
    fun rendererStringsMapToFamilies() {
        assertEquals(GpuFamily.MALI, GpuCompat.familyOf("Mali-G715"))
        assertEquals(GpuFamily.MALI, GpuCompat.familyOf("Immortalis-G925"))
        assertEquals(GpuFamily.ADRENO, GpuCompat.familyOf("Adreno (TM) 750"))
        assertEquals(GpuFamily.XCLIPSE, GpuCompat.familyOf("Samsung Xclipse 940"))
        assertEquals(GpuFamily.POWERVR, GpuCompat.familyOf("PowerVR D-Series DXT-48"))
        assertEquals(GpuFamily.UNKNOWN, GpuCompat.familyOf(null))
        assertEquals(GpuFamily.UNKNOWN, GpuCompat.familyOf(""))
    }

    @Test
    fun hardwareFallbackDetectsMali() {
        assertEquals(GpuFamily.MALI, GpuCompat.familyOf(null, "tensor"))
        assertEquals(GpuFamily.MALI, GpuCompat.familyOf("", "1c500000.mali"))
        assertEquals(GpuFamily.UNKNOWN, GpuCompat.familyOf(null, "qcom"))
    }

    @Test
    fun adrenoOnlyDriversAreRecognised() {
        for (driver in listOf("turnip", "adreno", "sd-8-elite", "wrapper-v2", "wrapper", "Wrapper-Gamenative")) {
            assertTrue(driver, GpuCompat.isAdrenoOnlyDriver(driver))
        }
        for (driver in listOf("vortek", "virgl", "zink", "", null)) {
            assertFalse(driver ?: "null", GpuCompat.isAdrenoOnlyDriver(driver))
        }
    }

    @Test
    fun maliBlocksRuntimeWrapperButNotSystem() {
        assertEquals(GpuFamily.MALI, GpuCompat.familyOf("Mali-G715"))
        assertTrue(GpuCompat.isBionicWrapperDriver("wrapper-v2"))
        assertFalse(GpuCompat.isBionicWrapperDriver("vortek"))
    }

    @Test
    fun systemWrapperIsNotAnAdrenoToolsPackage() {
        // The Bionic default is "Wrapper" on the built-in System driver: the shared wrapper over
        // the device's own Vulkan driver, which loads on Mali as well. Flagging it taught users to
        // ignore the warning that matters.
        assertFalse(GpuCompat.isAdrenoOnlyDriver("Wrapper", "System"))
        assertTrue(GpuCompat.isAdrenoOnlyDriver("Wrapper", "vulkan.adreno.so-819.2"))
        assertTrue(GpuCompat.isAdrenoOnlyDriver("Wrapper"))

        val issues = evaluate(GpuFamily.MALI, driver = "Wrapper", variant = Container.BIONIC, driverVersion = "System")
        assertFalse(issues.any { it.code == GpuIssueCode.BIONIC_WRAPPER_ON_NON_ADRENO })
    }

    @Test
    fun adrenoWrapperEnvVarsAreDroppedOnMali() {
        val env = "WRAPPER_MAX_IMAGE_COUNT=0 MESA_VK_WSI_PRESENT_MODE=mailbox WINEESYNC=1"
        val (sanitized, removed) = GpuCompat.sanitizedWrapperEnvVars(env)
        assertEquals(setOf("WRAPPER_MAX_IMAGE_COUNT", "MESA_VK_WSI_PRESENT_MODE"), removed.toSet())
        assertTrue(sanitized.contains("WINEESYNC=1"))
        // A deliberate image count is a choice, not a leftover.
        val (kept, none) = GpuCompat.sanitizedWrapperEnvVars("WRAPPER_MAX_IMAGE_COUNT=8")
        assertTrue(none.isEmpty())
        assertEquals("WRAPPER_MAX_IMAGE_COUNT=8", kept)
    }

    @Test
    fun maliBionicWrapperIsABlocker() {
        val issues = evaluate(GpuFamily.MALI, driver = "wrapper-v2", variant = Container.BIONIC)
        assertTrue(issues.any { it.code == GpuIssueCode.BIONIC_WRAPPER_ON_NON_ADRENO && it.code.blocker })
    }

    @Test
    fun maliGlibcTurnipIsABlocker() {
        val issues = evaluate(GpuFamily.MALI, driver = "turnip")
        assertTrue(issues.any { it.code == GpuIssueCode.ADRENO_ONLY_DRIVER && it.code.blocker })
    }

    @Test
    fun adrenoDeviceIsNeverBlocked() {
        // The exact Escape the Backrooms configuration, but on hardware that can run it.
        val issues = evaluate(
            GpuFamily.ADRENO,
            driver = "turnip",
            dxWrapper = "vkd3d",
            variant = Container.GLIBC,
            envVars = "TU_DEBUG=noconform ZINK_DESCRIPTORS=lazy",
        )
        assertTrue(issues.none { it.code.blocker })
        // Only the missing DXVK is worth mentioning on Adreno.
        assertEquals(setOf(GpuIssueCode.D3D11_WITHOUT_DXVK), codes(*issues.toTypedArray()))
    }

    @Test
    fun unknownGpuIsNeverBlocked() {
        val wrapper = evaluate(GpuFamily.UNKNOWN, driver = "wrapper-v2", variant = Container.BIONIC)
        assertTrue(wrapper.none { it.code.blocker })
        val turnip = evaluate(GpuFamily.UNKNOWN, driver = "turnip")
        assertTrue(turnip.none { it.code.blocker })
    }

    @Test
    fun turnipOnlyEnvVarsAreReportedAndStrippedOnMali() {
        val env = "TU_DEBUG=noconform TU_OVERRIDE_HEAP_SIZE=4096 ZINK_DESCRIPTORS=lazy WINEESYNC=1"
        val issues = evaluate(GpuFamily.MALI, driver = "vortek", envVars = env)
        val reported = issues.first { it.code == GpuIssueCode.TURNIP_ENV_ON_NON_ADRENO }
        assertFalse(reported.code.blocker)

        val (sanitized, removed) = GpuCompat.sanitizedEnvVars(env, GpuFamily.MALI)
        assertEquals(setOf("TU_DEBUG", "TU_OVERRIDE_HEAP_SIZE"), removed.toSet())
        assertFalse(sanitized.contains("TU_DEBUG"))
        assertFalse(sanitized.contains("TU_OVERRIDE_HEAP_SIZE"))
        // Zink knobs belong to Vortek and must survive.
        assertTrue(sanitized.contains("ZINK_DESCRIPTORS=lazy"))
        assertTrue(sanitized.contains("WINEESYNC=1"))
    }

    @Test
    fun adrenoEnvVarsAreLeftAlone() {
        val env = "TU_DEBUG=noconform ZINK_DESCRIPTORS=lazy"
        val (sanitized, removed) = GpuCompat.sanitizedEnvVars(env, GpuFamily.ADRENO)
        assertEquals(env, sanitized)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun missingDxvkIsAWarningOnly() {
        val vkd3dOnly = evaluate(GpuFamily.MALI, driver = "vortek", dxWrapper = "vkd3d")
        val dxvk = vkd3dOnly.first { it.code == GpuIssueCode.D3D11_WITHOUT_DXVK }
        assertFalse(dxvk.code.blocker)
        assertTrue(dxvk.detail.contains("D3D12"))

        val wined3d = evaluate(GpuFamily.MALI, driver = "vortek", dxWrapper = "wined3d")
        assertTrue(wined3d.any { it.code == GpuIssueCode.D3D11_WITHOUT_DXVK })

        val withDxvk = evaluate(GpuFamily.MALI, driver = "vortek", dxWrapper = "dxvk")
        assertTrue(withDxvk.none { it.code == GpuIssueCode.D3D11_WITHOUT_DXVK })
    }

    @Test
    fun oversizedDeviceMemoryIsAWarning() {
        val issues = evaluate(GpuFamily.MALI, driver = "vortek", maxDeviceMemoryMb = 4_096, totalRamMb = 8_000)
        assertTrue(issues.any { it.code == GpuIssueCode.VRAM_OVER_COMMIT && !it.code.blocker })

        val fitting = evaluate(GpuFamily.MALI, driver = "vortek", maxDeviceMemoryMb = 2_048, totalRamMb = 8_000)
        assertTrue(fitting.none { it.code == GpuIssueCode.VRAM_OVER_COMMIT })

        // No RAM report from the platform must not turn into a warning.
        val unknownRam = evaluate(
            GpuFamily.MALI,
            driver = "vortek",
            maxDeviceMemoryMb = 4_096,
            totalRamMb = 0,
        )
        assertTrue(unknownRam.none { it.code == GpuIssueCode.VRAM_OVER_COMMIT })
    }
}
