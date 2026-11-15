package app.gamenative.utils

import android.system.Os
import android.system.OsConstants
import timber.log.Timber
import java.io.File

/**
 * Kills helper processes that a previous session left behind, once, right before a new
 * container environment is created.
 *
 * The guests (wine's loader and wineserver, box64/FEX, proot, gamescope/Xwayland and the
 * pulse/alsa servers) are ordinary child processes of the app. When a session dies badly —
 * an activity kill, a crash in the guest, a force stop of the task — they survive: they keep
 * the GPU and audio nodes open, hold the shared-memory segments, and keep the Unix sockets in
 * the imagefs tmp dir bound, so the next launch collides with them instead of starting clean.
 *
 * The purge only touches processes that
 *  - run under this app's UID,
 *  - are not this process,
 *  - and either live under the app's imagefs or carry a known helper binary name.
 *
 * Zombies are reported but not killed: a zombie has already exited, and only its parent can
 * reap it (that is the JVM's process reaper for our own children).
 */
object StaleSessionProcesses {
    private const val TAG = "StaleProcesses"

    private val HELPER_NAMES = setOf(
        "wine", "wine64", "wine-preloader", "wineserver", "wineserver64",
        "box64", "box86", "box64-static", "box86-static",
        "proot", "proot-static",
        "gamescope", "Xwayland", "Xvfb",
        "pulseaudio", "pipewire", "alsa_server", "alsaserver",
        "fex", "fexcore", "FEXInterpreter",
    )

    /** A process whose command line points into the extracted container image is ours. */
    private const val IMAGEFS_MARKER = "/imagefs/"

    class Report(
        val scanned: Int,
        val killed: List<String>,
        val zombies: Int,
    )

    fun purge(imageFsRoot: File? = null): Report {
        val selfPid = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        val marker = imageFsRoot?.let { "${it.name}/" }?.let { "files/$it" } ?: IMAGEFS_MARKER
        val killed = ArrayList<String>()
        var scanned = 0
        var zombies = 0

        val pids = runCatching { File("/proc").list() }.getOrNull() ?: return Report(0, emptyList(), 0)
        for (entry in pids) {
            val pid = entry.toIntOrNull() ?: continue
            if (pid == selfPid) continue
            scanned++
            val cmdline = readCmdline(pid) ?: continue
            if (cmdline.isEmpty()) continue
            if (readState(pid) == 'Z') {
                zombies++
                continue
            }
            if (readUid(pid) != uid) continue
            val binary = cmdline.substringBefore(' ').substringAfterLast('/')
            val isOurs = binary in HELPER_NAMES || cmdline.contains(marker)
            if (!isOurs) continue
            runCatching { Os.kill(pid, OsConstants.SIGKILL) }
                .onSuccess { killed.add("$pid:$binary") }
                .onFailure { Timber.tag(TAG).w(it, "could not kill stale %s (%d)", binary, pid) }
        }

        val report = Report(scanned, killed, zombies)
        if (killed.isNotEmpty() || zombies > 0) {
            Timber.tag(TAG).i(
                "purged %d stale helper process(es) %s; %d zombie(s) still unreaped; scanned %d",
                killed.size, killed, zombies, scanned,
            )
        } else {
            Timber.tag(TAG).d("no stale helper processes; scanned %d", scanned)
        }
        return report
    }

    private fun readCmdline(pid: Int): String? = runCatching {
        File("/proc/$pid/cmdline").readBytes()
            .toString(Charsets.UTF_8)
            .trim('\u0000', '\n', ' ')
            .replace('\u0000', ' ')
            .trim()
    }.getOrNull()

    /** `state` is the third field of /proc/<pid>/stat, behind the parenthesised process name. */
    private fun readState(pid: Int): Char? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return@runCatching null
        stat[close + 2]
    }.getOrNull()

    private fun readUid(pid: Int): Int? = runCatching {
        File("/proc/$pid/status").readLines()
            .firstOrNull { it.startsWith("Uid:") }
            ?.removePrefix("Uid:")
            ?.trim()
            ?.substringBefore('\t')
            ?.substringBefore(' ')
            ?.toIntOrNull()
    }.getOrNull()
}
