package app.gamenative.utils

import app.gamenative.api.SupportSuggestion
import app.gamenative.ui.screen.support.SupportSuggestionApplier
import com.winlator.container.ContainerData
import com.winlator.core.envvars.EnvVars
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

/**
 * The advisor's two dangerous parts are the key mapping it writes through and the parser for what
 * a model returns. Both are pure functions over already-fetched data, so they are tested without a
 * device and without a network call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LaunchConfigAdvisorTest {

    private val base = ContainerData(
        dxwrapper = "dxvk",
        dxwrapperConfig = "version=2.4.1-gplasync,async=1",
        graphicsDriverConfig = "version=Turnip v26.2.0 R4",
        envVars = "DXVK_HUD=fps",
        videoMemorySize = "2048",
    )

    /* Applies through the same SupportSuggestionApplier path the support and optimize features
     * use, so what these assertions cover is exactly what the app will write. */
    private fun applied(entries: List<LaunchConfigAdvisor.Entry>): ContainerData {
        val edits = entries.mapNotNull { LaunchConfigAdvisor.editFor(it) }
        val updates = SupportSuggestionApplier.updatesFor(base, edits)
        return ContainerUtils.applyBestConfigMapToContainerData(base, updates)
    }

    @Test
    fun `a sub-key edit rewrites only that key and keeps the rest`() {
        val after = applied(listOf(entry("dxwrapperConfig.version", "1.11.1-sarek")))

        assertEquals("version=1.11.1-sarek,async=1", after.dxwrapperConfig)
    }

    @Test
    fun `unsetting a sub-key drops it without leaving a dangling comma`() {
        val after = applied(listOf(entry("dxwrapperConfig.async", null)))

        assertEquals("version=2.4.1-gplasync", after.dxwrapperConfig)
    }

    @Test
    fun `an env var edit is merged into the existing variable list`() {
        val after = applied(
            listOf(LaunchConfigAdvisor.Entry(SupportSuggestion.ENV_KEY, "DXVK_WSI_PRESENT_MODE", "fifo", null, "why")),
        )

        assertEquals("fifo", EnvVars(after.envVars).get("DXVK_WSI_PRESENT_MODE"))
        assertEquals("fps", EnvVars(after.envVars).get("DXVK_HUD"))
    }

    @Test
    fun `a top level edit lands on the container field`() {
        val after = applied(listOf(entry("videoMemorySize", "1024"), entry("startupSelection", "1")))

        assertEquals("1024", after.videoMemorySize)
        assertEquals(1, after.startupSelection.toInt())
    }

    @Test
    fun `unknown keys never reach the container`() {
        val after = applied(listOf(entry("notARealKey", "x")))

        assertEquals(base, after)
    }

    @Test
    fun `a set-then-cleared sub-key comes back absent`() {
        val set = applied(listOf(entry("graphicsDriverConfig.presentMode", "mailbox")))
        assertEquals("mailbox", set.graphicsDriverConfig.substringAfter("presentMode=").substringBefore(","))

        val cleared = applied(listOf(entry("graphicsDriverConfig.presentMode", null)))
        assertFalse(cleared.graphicsDriverConfig.contains("presentMode"))
        assertEquals("version=Turnip v26.2.0 R4", cleared.graphicsDriverConfig)
    }

    @Test
    fun `the parser accepts a fenced answer and rejects keys the applier does not know`() {
        val content = """
            Here you go:
            ```json
            {"summary":"s","changes":[
              {"key":"dxwrapperConfig.version","to":"2.4.1-gplasync","why":"newer"},
              {"key":"bogusKey","to":"x","why":"nope"},
              {"key":"envVars","name":"DXVK_ASYNC","to":"1","why":"ok"}
            ]}
            ```
        """.trimIndent()

        val entries = LaunchConfigAdvisor.parseChanges(content, base)

        assertEquals(listOf("dxwrapperConfig.version", SupportSuggestion.ENV_KEY), entries.map { it.key })
        assertEquals("DXVK_ASYNC", entries[1].name)
        assertFalse(entries[0].confident)
    }

    @Test
    fun `the parser returns nothing rather than throwing on prose`() {
        assertTrue(LaunchConfigAdvisor.parseChanges("I cannot help with that.", base).isEmpty())
        assertTrue(LaunchConfigAdvisor.parseChanges("""{"changes": "not an array"}""", base).isEmpty())
    }

    @Test
    fun `an env change without a name is dropped and a bare key is kept`() {
        val json = JSONObject().apply {
            put(
                "changes",
                org.json.JSONArray().apply {
                    // No "name": nothing could be written for envVars.
                    put(JSONObject().put("key", "envVars").put("to", "1"))
                    // A bare container key is a whole-value set, and is allowed.
                    put(JSONObject().put("key", "dxwrapperConfig").put("to", "version=2.4.1-gplasync"))
                    // A dot with nothing after it addresses no sub-key.
                    put(JSONObject().put("key", "graphicsDriverConfig.").put("to", "x"))
                },
            )
        }

        val entries = LaunchConfigAdvisor.parseChanges(json.toString(), base)

        assertEquals(listOf("dxwrapperConfig"), entries.map { it.key })
        assertEquals("Suggested by the model.", entries.single().why)
    }

    private fun entry(key: String, to: String?) =
        LaunchConfigAdvisor.Entry(key = key, name = null, to = to, from = null, why = "test")
}
