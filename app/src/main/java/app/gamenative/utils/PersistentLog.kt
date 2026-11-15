package app.gamenative.utils

import android.content.Context
import android.os.Build
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log written straight to internal storage, one flushed line at a time.
 *
 * Everything else this app logs is either in memory ([Timber]) or in logcat, and both are gone
 * the moment the process dies without a chance to run code - a native crash in the renderer, a
 * low-memory kill, or a kernel panic that reboots the phone. Those are exactly the sessions a
 * user wants a log of, so the lines are written and flushed as they happen, and WARN/ERROR lines
 * are fsynced as well, which also survives a reboot.
 *
 * A session marker next to the logs records the boot id (/proc/sys/kernel/random/boot_id), so the
 * next start can tell the three endings apart: the marker missing is a normal end, the same boot
 * id with the marker still there is a process that was killed, and a different boot id means the
 * device rebooted underneath the session.
 */
object PersistentLog {
    private const val TAG = "PersistentLog"
    private const val DIR = "logs"
    private const val PREFIX = "app-"
    private const val MARKER = "session_open"
    private const val MAX_FILE_BYTES = 4L * 1024L * 1024L
    private const val MAX_FILES = 4
    private const val SESSION_END_MARK = "SESSION-END"
    private const val EXPORT_TAIL_BYTES = 400L * 1024L

    /** How the previous process ended, as far as this log can tell. */
    enum class PreviousEnd { NONE, CLEAN, KILLED, REBOOT }

    data class LastSession(
        val end: PreviousEnd,
        val file: File?,
        val startedAt: Long,
        val bootId: String?,
    )

    private val lock = Any()
    private var stream: FileOutputStream? = null
    private var writer: OutputStreamWriter? = null
    private var currentFile: File? = null
    private var writtenBytes = 0L
    private var installed = false
    private var sessionStartMillis = 0L

    /** The previous session, resolved once during [install]. */
    @Volatile
    var lastSession: LastSession? = null
        private set

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /**
     * Opens a new session log and plants the tree. Safe to call more than once; only the first
     * call does anything.
     */
    fun install(context: Context) {
        if (installed) return
        val appContext = context.applicationContext
        synchronized(lock) {
            if (installed) return
            val directory = dir(appContext)
            lastSession = resolvePrevious(directory)
            prune(directory)
            sessionStartMillis = System.currentTimeMillis()
            openNewFileLocked(directory)
            installed = true
            writeMarkerLocked(directory, bootId())
        }

        Timber.plant(PersistentLogTree())
        write(
            Log.INFO,
            TAG,
            "session start ${timeText()} (${Build.MODEL}, Android ${Build.VERSION.RELEASE})",
        )
        lastSession?.let { previous ->
            write(Log.INFO, TAG, "previous session: ${previous.end} (${previous.file?.name ?: "no log"})")
        }
    }

    /** Runs a line through the same file the tree writes to. */
    fun write(priority: Int, tag: String?, message: String) {
        if (!installed) return
        val body = if (message.indexOf('\n') >= 0) message.replace("\n", "\n    ") else message
        val line = "${timeText()} ${priorityLetter(priority)}/${tag ?: "?"}: $body"

        synchronized(lock) {
            val out = writer ?: return
            val fileStream = stream ?: return
            runCatching {
                out.write(line)
                out.write("\n")
                out.flush()
                writtenBytes += line.length + 1
                // A warning is usually the line that explains what came next; make it durable
                // even if the device dies before the next flush reaches the disk.
                if (priority >= Log.WARN) fileStream.fd.sync()
                if (writtenBytes >= MAX_FILE_BYTES) {
                    val directory = currentFile?.parentFile
                    closeLocked()
                    if (directory != null) {
                        prune(directory)
                        openNewFileLocked(directory)
                    }
                }
            }
        }
    }

    /**
     * Marks the session as finished, so the next start does not report it as abnormal. Called on
     * a best-effort basis: Android usually kills the process without letting this run.
     */
    fun markSessionEnd(context: Context) {
        if (!installed) return
        write(Log.INFO, TAG, SESSION_END_MARK)
        synchronized(lock) {
            runCatching { stream?.fd?.sync() }
            closeLocked()
            runCatching { File(dir(context), MARKER).delete() }
        }
        installed = false
    }

    /** Newest log files, most recent first. */
    fun logFiles(context: Context): List<File> = logFilesIn(dir(context))

    private fun logFilesIn(directory: File): List<File> =
        directory.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** The tail of the newest session log, for a report or the export bundle. */
    fun exportTail(context: Context, maxBytes: Long = EXPORT_TAIL_BYTES): String {
        val file = logFiles(context).firstOrNull() ?: return "(no persistent log yet)"
        return export(context, file, maxBytes)
    }

    /**
     * The tail of [file]. This is what matters after a crash: the newest file is the session the
     * user is looking at, while the previous one holds the last lines before the process died.
     */
    fun export(context: Context, file: File?, maxBytes: Long = EXPORT_TAIL_BYTES): String {
        val target = file ?: return "(no log file)"
        if (!target.isFile) return "(${target.name} is gone)"
        return runCatching { readTail(target, maxBytes) }
            .getOrElse { "Failed to read ${target.name}: ${it.message}" }
    }

    /** Everything the previous session wrote, newest first, as a list of files. */
    fun previousLogFiles(context: Context): List<File> = logFiles(context)

    private fun openNewFileLocked(directory: File) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var target = File(directory, "$PREFIX$stamp.log")
        var index = 1
        while (target.exists() && target.length() > 0) {
            target = File(directory, "$PREFIX$stamp-$index.log")
            index += 1
        }
        currentFile = target
        writtenBytes = target.length()
        runCatching {
            stream = FileOutputStream(target, true)
            writer = OutputStreamWriter(stream!!, Charsets.UTF_8)
        }.onFailure {
            stream = null
            writer = null
        }
    }

    private fun closeLocked() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        runCatching { stream?.close() }
        writer = null
        stream = null
    }

    private fun writeMarkerLocked(directory: File, id: String?) {
        val marker = File(directory, MARKER)
        val name = currentFile?.name ?: ""
        runCatching {
            marker.writeText("$name\n$sessionStartMillis\n${id.orEmpty()}")
        }
    }

    /** Resolves the previous session from the marker, then from the newest log's last line. */
    private fun resolvePrevious(directory: File): LastSession? {
        val marker = File(directory, MARKER)
        if (!marker.exists()) {
            return LastSession(PreviousEnd.NONE, logFilesIn(directory).firstOrNull(), 0L, null)
        }

        val lines = runCatching { marker.readLines() }.getOrDefault(emptyList())
        val name = lines.getOrNull(0).orEmpty().trim()
        val startedAt = lines.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
        val recordedBootId = lines.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        runCatching { marker.delete() }

        val file = File(directory, name).takeIf { name.isNotEmpty() && it.exists() }
            ?: logFilesIn(directory).firstOrNull()

        val ended = file?.let { lastLine(it).contains(SESSION_END_MARK) } ?: false
        val end = classifyEnd(recordedBootId, bootId(), ended)
        return LastSession(end, file, startedAt, recordedBootId)
    }

    /**
     * How the previous session ended. A different boot id means the device restarted under it,
     * which no amount of in-app error handling can explain away.
     */
    internal fun classifyEnd(
        recordedBootId: String?,
        currentBootId: String?,
        endedCleanly: Boolean,
    ): PreviousEnd = when {
        recordedBootId != null && currentBootId != null && recordedBootId != currentBootId ->
            PreviousEnd.REBOOT
        endedCleanly -> PreviousEnd.CLEAN
        else -> PreviousEnd.KILLED
    }

    private fun prune(directory: File) {
        logFilesIn(directory).drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    private fun lastLine(file: File): String = runCatching {
        val length = file.length()
        val window = minOf(length, 2_048L)
        if (window <= 0L) return ""
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - window)
            val bytes = ByteArray(window.toInt())
            raf.readFully(bytes)
            bytes.toString(Charsets.UTF_8)
        }
    }.getOrDefault("")

    private fun readTail(file: File, maxBytes: Long): String {
        val length = file.length()
        if (length <= maxBytes) return file.readText(Charsets.UTF_8)
        val start = (length - maxBytes).coerceAtLeast(0L)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            val firstBreak = text.indexOf('\n')
            return if (firstBreak >= 0) text.substring(firstBreak + 1) else text
        }
    }

    /** `/proc/sys/kernel/random/boot_id` changes on every boot, which is how a reboot is spotted. */
    private fun bootId(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun priorityLetter(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> 'I'
    }

    private fun timeText(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}

/** Timber tree that feeds [PersistentLog]. INFO and above: the file has to stay small. */
class PersistentLogTree : Timber.Tree() {
    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.INFO

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        PersistentLog.write(priority, tag, message)
        t?.let { PersistentLog.write(priority, tag, Log.getStackTraceString(it)) }
    }
}
