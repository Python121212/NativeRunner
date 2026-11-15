package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A component download that is not the file it claims to be is the reason an arm64ec DXVK install
 * used to fail with nothing in the log. These tests pin down the sniffing that names it.
 */
class ManifestInstallerArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(vararg bytes: Int): File {
        val f = temp.newFile()
        f.writeBytes(bytes.map { it.toByte() }.toByteArray())
        return f
    }

    @Test
    fun zstdTarIsRecognised() {
        assertEquals(
            ManifestInstaller.ArchiveKind.ZSTD,
            ManifestInstaller.archiveKind(file(0x28, 0xB5, 0x2F, 0xFD, 0x00, 0x01)),
        )
    }

    @Test
    fun xzIsRecognised() {
        assertEquals(
            ManifestInstaller.ArchiveKind.XZ,
            ManifestInstaller.archiveKind(file(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)),
        )
    }

    @Test
    fun zipIsRecognised() {
        assertEquals(
            ManifestInstaller.ArchiveKind.ZIP,
            ManifestInstaller.archiveKind(file(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)),
        )
    }

    @Test
    fun htmlIsCalledOut() {
        // What GitHub answers with when a release asset is behind a sign-in page.
        assertEquals(
            ManifestInstaller.ArchiveKind.HTML,
            ManifestInstaller.archiveKind(file('<'.code, '!'.code, 'D'.code, 'O'.code, 'C'.code, 'T'.code)),
        )
    }

    @Test
    fun missingOrTinyFileIsUnknown() {
        assertEquals(ManifestInstaller.ArchiveKind.UNKNOWN, ManifestInstaller.archiveKind(null))
        assertEquals(ManifestInstaller.ArchiveKind.UNKNOWN, ManifestInstaller.archiveKind(temp.newFile()))
        val oneByte = temp.newFile()
        oneByte.writeBytes(byteArrayOf(0x1F))
        assertEquals(ManifestInstaller.ArchiveKind.UNKNOWN, ManifestInstaller.archiveKind(oneByte))
    }

    @Test
    fun gzipIsNotMistakenForHtml() {
        val kind = ManifestInstaller.archiveKind(file(0x1F, 0x8B, 0x08, 0x00, 0x00, 0x00))
        assertEquals(ManifestInstaller.ArchiveKind.GZIP, kind)
        assertFalse(kind == ManifestInstaller.ArchiveKind.HTML)
        assertTrue(kind != ManifestInstaller.ArchiveKind.ZIP)
    }
}
