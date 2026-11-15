package app.gamenative.utils

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A .wcp is not always a tar.xz/zst. The importer used to answer "could not be read" for a plain
 * zip - and for a zip with a top level folder - which is what made an arm64ec DXVK impossible to
 * install by hand as well as automatically.
 */
@RunWith(RobolectricTestRunner::class)
class ContentsManagerZipInstallTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun profileJson(): String =
        """
        {
          "type": "DXVK",
          "versionName": "2.6.2-arm64ec-gplasync",
          "versionCode": 1,
          "description": "test build",
          "files": [
            {"source": "d3d11.dll", "target": "${'$'}{system32}/d3d11.dll"}
          ]
        }
        """.trimIndent()

    /** [folder] null puts profile.json at the archive root, otherwise one level down. */
    private fun zipPackage(folder: String?): File {
        val zip = File(temp.root, "dxvk.zip")
        val prefix = if (folder == null) "" else "$folder/"
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("${prefix}profile.json"))
            out.write(profileJson().toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("${prefix}d3d11.dll"))
            out.write(ByteArray(64) { it.toByte() })
            out.closeEntry()
        }
        return zip
    }

    private fun install(zip: File): Pair<ContentProfile?, ContentsManager.InstallFailedReason?> {
        val mgr = ContentsManager(context)
        var profile: ContentProfile? = null
        var reason: ContentsManager.InstallFailedReason? = null
        val latch = CountDownLatch(1)
        mgr.extraContentFile(Uri.fromFile(zip), object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(failed: ContentsManager.InstallFailedReason, e: Exception?) {
                reason = failed
                latch.countDown()
            }

            override fun onSucceed(succeeded: ContentProfile) {
                profile = succeeded
                latch.countDown()
            }
        })
        latch.await(30, TimeUnit.SECONDS)
        return profile to reason
    }

    @Test
    fun zipAtTheArchiveRootIsAccepted() {
        val (profile, reason) = install(zipPackage(null))
        assertNull("install failed with $reason", reason)
        assertNotNull(profile)
        assertEquals("2.6.2-arm64ec-gplasync", profile!!.verName)
    }

    @Test
    fun zipWithATopLevelFolderIsFlattenedAndAccepted() {
        val (profile, reason) = install(zipPackage("Dxvk-2.6.2-arm64ec-gplasync"))
        assertNull("install failed with $reason", reason)
        assertNotNull(profile)
        assertEquals("2.6.2-arm64ec-gplasync", profile!!.verName)
    }
}
