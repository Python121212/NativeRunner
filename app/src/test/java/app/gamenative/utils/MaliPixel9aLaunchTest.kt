package app.gamenative.utils

import com.winlator.container.Container
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch that stopped on "A D3D11-compatible GPU (Feature Level 11_0, Shader Model 5.0) is
 * required", reproduced from the container the device uploaded.
 *
 * The device is a Pixel 9a (Mali-G715). The container was a Bionic one with the built-in `Wrapper`
 * driver (`version=System`), arm64ec Wine, an arm64ec DXVK, and the env vars of an Adreno recipe
 * copied along. On that hardware the recipe's knobs break presentation - an image count of 0 and a
 * pinned present mode override the container's own settings - and the old guard also refused the
 * launch with a warning that did not apply, which taught the user to ignore it.
 *
 * These are the rules the launch now applies, kept here so a Pixel 9a configuration cannot silently
 * regress again.
 */
class MaliPixel9aLaunchTest {

    private val pixel9aEnv = "WRAPPER_MAX_IMAGE_COUNT=0 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact " +
        "MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true " +
        "WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform PULSE_LATENCY_MSEC=144"

    @Test
    fun pixel9aIsMali() {
        assertEquals(GpuFamily.MALI, GpuCompat.familyOf("Mali-G715", "tego"))
    }

    @Test
    fun theAdrenoRecipeKnobsAreDroppedOnPixel9a() {
        val (withoutTurnip, turnipRemoved) = GpuCompat.sanitizedEnvVars(pixel9aEnv, GpuFamily.MALI)
        val (clean, wrapperRemoved) = GpuCompat.sanitizedWrapperEnvVars(withoutTurnip)

        assertEquals(setOf("TU_DEBUG"), turnipRemoved.toSet())
        assertEquals(
            setOf("WRAPPER_MAX_IMAGE_COUNT", "MESA_VK_WSI_PRESENT_MODE"),
            wrapperRemoved.toSet(),
        )
        // What the launch still needs survives.
        assertTrue(clean.contains("WINEESYNC=1"))
        assertTrue(clean.contains("PULSE_LATENCY_MSEC=144"))
        // Zink knobs are shared with Vortek, so they are not touched here.
        assertTrue(clean.contains("ZINK_DESCRIPTORS=lazy"))
        // The presentation settings now come from the container alone.
        assertFalse(clean.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(clean.contains("WRAPPER_MAX_IMAGE_COUNT"))
        assertFalse(clean.contains("TU_DEBUG"))
    }

    @Test
    fun theSystemWrapperIsNotRefusedOnPixel9a() {
        val issues = GpuCompat.evaluate(
            family = GpuFamily.MALI,
            containerVariant = Container.BIONIC,
            graphicsDriver = "Wrapper",
            dxWrapper = "dxvk",
            envVarsText = pixel9aEnv,
            maxDeviceMemoryMb = 0,
            totalRamMb = 8_192,
            graphicsDriverVersion = "System",
        )
        assertFalse(issues.any { it.code == GpuIssueCode.BIONIC_WRAPPER_ON_NON_ADRENO })
        assertFalse(issues.any { it.code.blocker })
        // The leftover env vars are still reported, so the sanitizer's work is visible in the log.
        assertTrue(issues.any { it.code == GpuIssueCode.TURNIP_ENV_ON_NON_ADRENO })
    }

    @Test
    fun anAdrenoWrapperPackageIsStillRefusedOnPixel9a() {
        val issues = GpuCompat.evaluate(
            family = GpuFamily.MALI,
            containerVariant = Container.BIONIC,
            graphicsDriver = "Wrapper",
            dxWrapper = "dxvk",
            envVarsText = "",
            maxDeviceMemoryMb = 0,
            totalRamMb = 8_192,
            graphicsDriverVersion = "vulkan.adreno.so-819.2",
        )
        assertTrue(issues.any { it.code == GpuIssueCode.BIONIC_WRAPPER_ON_NON_ADRENO && it.code.blocker })
    }

    @Test
    fun vkd3dOnlyLeavesDirect3D11WithoutDxvk() {
        // The container that started this: -dx11 with the D3D12-only wrapper.
        val issues = GpuCompat.evaluate(
            family = GpuFamily.MALI,
            containerVariant = Container.BIONIC,
            graphicsDriver = "Wrapper",
            dxWrapper = "vkd3d",
            envVarsText = "",
            maxDeviceMemoryMb = 0,
            totalRamMb = 8_192,
            graphicsDriverVersion = "System",
        )
        assertTrue(issues.any { it.code == GpuIssueCode.D3D11_WITHOUT_DXVK })
    }
}
