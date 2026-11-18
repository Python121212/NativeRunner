package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The condensed log is what the model sees and what the viewer shows, so its two promises are
 * tested here: a failure line survives, and repetition does not.
 */
class LaunchLogExcerptTest {

    private val log = """
        starting wine
        fixme:ntdll:create_logical_proc_info
        err:module:import_dll Library d3d11.dll not found
        warn: DXVK: vkCreateSwapchainKHR failed
        err:module:import_dll Library d3d11.dll not found
        frame 1234 drawn
        err:vulkan:vkAllocateMemory failed
        terminating with status 137
    """.trimIndent()

    @Test
    fun `problem lines come first and the last line survives`() {
        val text = LaunchLogExcerpt.build(log)

        assertTrue(text.indexOf("d3d11.dll not found") < text.indexOf("--- last"))
        assertTrue(text.trimEnd().endsWith("terminating with status 137"))
    }

    @Test
    fun `a repeated problem line is counted instead of repeated`() {
        val text = LaunchLogExcerpt.build(log)
        val problems = text.substringBefore("--- last")

        // Once in the problem section, carrying the count; the tail still has the raw copies.
        assertEquals(1, problems.lines().count { it.contains("d3d11.dll not found") })
        assertTrue(problems.contains("[x2]"))
    }

    @Test
    fun `a line that only differs by address counts as the same problem`() {
        val a = "err:vulkan:device lost at 0x7f00112233"
        val b = "err:vulkan:device lost at 0x7f44556677"

        assertEquals(LaunchLogExcerpt.normalise(a), LaunchLogExcerpt.normalise(b))
    }

    @Test
    fun `routine output is not mistaken for a problem`() {
        assertFalse(LaunchLogExcerpt.isProblem("fixme:ntdll:create_logical_proc_info"))
        assertTrue(LaunchLogExcerpt.isProblem("err:module:import_dll Library d3d11.dll not found"))
        assertFalse(LaunchLogExcerpt.isProblem(""))
    }

    @Test
    fun `a log with no failure line says so instead of pretending`() {
        val text = LaunchLogExcerpt.build("starting\nrunning fine\n")

        assertTrue(text.contains("no line that looks like a failure"))
        assertTrue(text.contains("running fine"))
    }

    @Test
    fun `an empty log condenses to nothing`() {
        assertEquals("", LaunchLogExcerpt.build(""))
    }
}
