package app.gamenative.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import androidx.core.content.FileProvider
import app.gamenative.BuildConfig
import app.gamenative.CrashHandler
import app.gamenative.R
import com.winlator.core.GPUInformation
import com.winlator.xenvironment.ImageFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds one text file with everything needed to explain a bad session and hands it to the share
 * sheet.
 *
 * The sections are ordered by how much they are worth when the app is gone: why the previous
 * process ended, the persistent log (the only trail that survives a reboot or a native crash),
 * the crash reports, the wine log, the Android exit records and finally logcat.
 */
object LogExporter {
    private const val EXPORT_DIR = "logs-export"
    private const val WINEG_LOG_TAIL_BYTES = 192L * 1024L
    private const val LOGCAT_LINES = 800
    private const val CRASH_REPORTS = 2
    private const val EXIT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** Writes the bundle into the cache dir (shareable through the app's FileProvider). */
    suspend fun export(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val directory = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            directory.listFiles()?.forEach { runCatching { it.delete() } }

            val target = File(directory, "gamenative-logs-$stamp.txt")
            target.bufferedWriter().use { out ->
                out.write("===== GameNative log bundle =====\n")
                out.write("generated: ${Date()} \n\n")

                writeSection(out, "build") {
                    it.write("version     : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                    it.write("package     : ${BuildConfig.APPLICATION_ID}\n")
                    it.write("debug build : ${BuildConfig.DEBUG}\n")
                }

                writeSection(out, "device") {
                    it.write("model       : ${Build.MANUFACTURER} ${Build.MODEL}\n")
                    it.write("device      : ${Build.DEVICE} / ${Build.HARDWARE}\n")
                    it.write("android     : ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.joinToString(",")})\n")
                    it.write("gpu         : ${runCatching { GPUInformation.getRenderer(context) }.getOrNull() ?: "?"}\n")
                    it.write("ram         : ${totalRamMb(context)} MB total, ${availableRamMb(context)} MB available\n")
                    it.write("storage     : ${freeStorageMb(context)} MB free\n")
                }

                writeSection(out, "previous session") {
                    val previous = PersistentLog.lastSession
                    if (previous == null) {
                        it.write("no session marker recorded\n")
                    } else {
                        it.write("end         : ${previous.end}\n")
                        it.write("log         : ${previous.file?.absolutePath ?: "none"}\n")
                        if (previous.startedAt > 0) it.write("started     : ${Date(previous.startedAt)}\n")
                        it.write("boot id     : ${previous.bootId ?: "unknown"}\n")
                    }
                }

                writeSection(out, "persistent log (tail)") {
                    it.write(PersistentLog.exportTail(context))
                    it.write("\n")
                }

                writeSection(out, "crash reports") {
                    val crashes = crashFiles(context).take(CRASH_REPORTS)
                    if (crashes.isEmpty()) {
                        it.write("(none)\n")
                    } else {
                        crashes.forEach { file ->
                            it.write("--- ${file.name} (${file.length()} bytes) ---\n")
                            it.write(readTail(file, WINEG_LOG_TAIL_BYTES))
                            it.write("\n")
                        }
                    }
                }

                writeSection(out, "wine log (tail)") {
                    val wineLog = File(File(context.getExternalFilesDir(null), "wine_logs"), "wine_debug.log")
                    if (!wineLog.exists()) {
                        it.write("(none)\n")
                    } else {
                        it.write("--- ${wineLog.name} (${wineLog.length()} bytes) ---\n")
                        it.write(readTail(wineLog, WINEG_LOG_TAIL_BYTES))
                        it.write("\n")
                    }
                }

                writeSection(out, "android exit records") {
                    val exits = runCatching {
                        AppExitRecorder.recentExits(
                            context,
                            System.currentTimeMillis() - EXIT_WINDOW_MS,
                            limit = 5,
                        )
                    }.getOrDefault(emptyList())
                    if (exits.isEmpty()) {
                        it.write("(none)\n")
                    } else {
                        exits.forEach { record -> it.write(record.toString(2) + "\n") }
                    }
                }

                writeSection(out, "logcat (this process)") {
                    it.write(runCatching { CrashHandler.getAppLogs(LOGCAT_LINES) }.getOrDefault("(unavailable)"))
                    it.write("\n")
                }
            }
            target
        } catch (e: Exception) {
            Timber.tag("LogExporter").e(e, "Failed to build the log bundle")
            null
        }
    }

    /** Opens the share sheet for an exported bundle. */
    fun share(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                putExtra(Intent.EXTRA_TEXT, context.getString(R.string.log_export_share_text))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.log_export_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Timber.tag("LogExporter").e(e, "Failed to share the log bundle")
        }
    }

    fun crashFiles(context: Context): List<File> =
        File(context.getExternalFilesDir(null), "crash_logs")
            .listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** ImageFs diagnostics left by a wrapper debug run, if the game wrote one. */
    fun diagnosticsFile(context: Context, appId: String): File? =
        runCatching { DiagnosticsLog.file(context, appId).takeIf { it.exists() } }.getOrNull()

    fun rootDir(context: Context): File = runCatching { ImageFs.find(context).rootDir }.getOrDefault(File("/"))

    private fun writeSection(
        out: java.io.BufferedWriter,
        title: String,
        block: (java.io.BufferedWriter) -> Unit,
    ) {
        out.write("\n===== $title =====\n")
        runCatching { block(out) }
            .onFailure { out.write("(section failed: ${it.message})\n") }
    }

    private fun readTail(file: File, maxBytes: Long): String = runCatching {
        val length = file.length()
        if (length <= maxBytes) return file.readText(Charsets.UTF_8)
        val start = (length - maxBytes).coerceAtLeast(0L)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            bytes.toString(Charsets.UTF_8)
        }
    }.getOrElse { "(read failed: ${it.message})" }

    private fun totalRamMb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            am.getMemoryInfo(info)
            (info.totalMem / (1024L * 1024L)).toInt()
        }.getOrDefault(0)
    }

    private fun availableRamMb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            am.getMemoryInfo(info)
            (info.availMem / (1024L * 1024L)).toInt()
        }.getOrDefault(0)
    }

    private fun freeStorageMb(context: Context): Long = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBytes / (1024L * 1024L)
    }.getOrDefault(0L)
}
