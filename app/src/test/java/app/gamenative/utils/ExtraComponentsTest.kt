package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arm64ec DXVK entries are what makes Direct3D 11 work in an arm64ec container, so the
 * identification rules and the shape they are offered to the manifest in both need to hold.
 */
class ExtraComponentsTest {

    @Test
    fun theRecommendedBuildIsAnArm64ecEntry() {
        assertTrue(ExtraComponents.isArm64ecComponent(ExtraComponents.RECOMMENDED_ARM64EC_DXVK))
        assertTrue(ExtraComponents.RECOMMENDED_ARM64EC_DXVK.startsWith("2.6"))
    }

    @Test
    fun x86_64BuildsAreNotMistakenForArm64ec() {
        for (version in listOf("2.6.1-gplasync", "2.4.1", "1.10.3", "async-1.10.3", "")) {
            assertFalse(version, ExtraComponents.isArm64ecComponent(version))
        }
    }

    @Test
    fun theLaunchPathFormIsRecognised() {
        assertTrue(ExtraComponents.isArm64ecDxWrapper("dxvk-${ExtraComponents.RECOMMENDED_ARM64EC_DXVK}"))
        assertFalse(ExtraComponents.isArm64ecDxWrapper("dxvk-2.6.1-gplasync"))
        assertFalse(ExtraComponents.isArm64ecDxWrapper("vkd3d-2.14.1"))
        assertFalse(ExtraComponents.isArm64ecDxWrapper(null))
    }

    @Test
    fun everyEntryIsOfferedAsADownloadableDxvkWithAnArm64ecTag() {
        val additions = ExtraComponents.manifestAdditions()
        val dxvk = additions[ManifestContentTypes.DXVK].orEmpty()
        assertEquals(ExtraComponents.ARM64EC_DXVK.size, dxvk.size)
        for (entry in dxvk) {
            assertTrue(entry.id, entry.url.startsWith("https://github.com/"))
            assertTrue(entry.id, entry.url.endsWith(".wcp"))
            assertEquals("arm64ec", entry.arch)
            assertEquals("bionic", entry.variant)
        }
    }
}
