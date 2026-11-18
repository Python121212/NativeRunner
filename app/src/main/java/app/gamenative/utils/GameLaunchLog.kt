package app.gamenative.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.FileProvider
import app.gamenative.R
import com.winlator.container.ContainerData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One text file per launch, holding only that game's session: the guest output (wine, DXVK,
 * VKD3D, the emulator) plus the configuration the session started with.
 *
 * The persistent log is a single trail for the whole app and rotates, so a user who wants to know
 * why *this* game did not start has to pick the right lines out of everybody else's. This file is
 * written from the moment the container is prepared until the session ends, it is flushed line by
 * line, and it stays until a newer launch of the same game pushes it out.
 *
 * Two things make it readable afterwards: every phase the launcher goes through is marked (so a
 * run that stopped during "preparing the environment" is obvious from the tail), and the header
 * carries the numbers a diagnosis needs (available RAM, free storage, Vulkan API level) next to the
 * settings the run actually used.
 */
object GameLaunchLog {
    private const val TAG = "GameLaunchLog"
    private const val DIR = "game_logs"
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val MAX_SESSIONS = 5
    private const val EXPORT_BYTES = 2L * 1024 * 1024
    /** How much of the log the AI check reads. Older lines almost never explain the failure. */
    const val ANALYSIS_BYTES = 24L * 1024

    private val lock = Any()
    private var stream: FileOutputStream? = null
    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var currentAppId: String? = null
    private var writtenBytes = 0L
    private var startedAt = 0L
    private var truncated = false
    private var lastPhase = ""

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** `appId` goes into a file name, so keep it to characters every file system accepts. */
    private fun safeId(appId: String): String = appId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun filesOf(directory: File, appId: String): List<File> =
        directory.listFiles { file -> file.isFile && file.name.startsWith("${safeId(appId)}-") }
            ?.sortedByDescending { it.name }
            .orEmpty()

    /** The most recent launch of [appId], or null when the game was never started. */
    fun newest(context: Context, appId: String): File? = filesOf(dir(context), appId).firstOrNull()

    fun sessions(context: Context, appId: String): List<File> = filesOf(dir(context), appId)

    /** Every game's newest launch log, most recent first. Only for the whole-app log bundle. */
    fun logFiles(context: Context, limit: Int = 3): List<File> =
        dir(context).listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            .orEmpty()

    /** True while a launch is being written, i.e. the game is (or is being) started. */
    val recording: Boolean get() = currentAppId != null

    /** The file being written right now, for a viewer that opens while the game is still running. */
    fun currentSession(): File? = currentFile

    /**
     * Opens a fresh file for this launch. Called when the container is about to be prepared, so
     * anything the launch itself logs ends up in the file, not just the game's own output.
     */
    fun begin(context: Context, appId: String) {
        val appContext = context.applicationContext
        runCatching {
            val directory = dir(appContext)
            val container = runCatching { ContainerUtils.getContainer(appContext, appId) }.getOrNull()
            val data = container?.let { runCatching { ContainerUtils.toContainerData(it) }.getOrNull() }
            val name = runCatching { ContainerUtils.resolveGameName(appId) }.getOrDefault(appId)
            synchronized(lock) {
                closeLocked()
                prune(directory, appId)
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(directory, "${safeId(appId)}-$stamp.log")
                currentFile = file
                currentAppId = appId
                writtenBytes = 0L
                truncated = false
                lastPhase = ""
                startedAt = System.currentTimeMillis()
                runCatching {
                    FileOutputStream(file, false).let { out ->
                        stream = out
                        writer = OutputStreamWriter(out, Charsets.UTF_8).buffered()
                    }
                }.onFailure {
                    writer = null
                    stream = null
                    currentFile = null
                    currentAppId = null
                    Timber.tag(TAG).w(it, "could not open the launch log for $appId")
                }
            }
            writeRaw("=== GameNative launch log ===")
            writeRaw("game        : $name ($appId)")
            writeRaw("started     : ${Date()}")
            writeRaw("device      : ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE} / ${Build.HARDWARE}")
            writeRaw("android     : ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.joinToString(",")})")
            writeRaw("soc         : ${HardwareUtils.getSOCName() ?: "unknown"}")
            writeRaw(
                "gpu         : " +
                    runCatching { com.winlator.core.GPUInformation.getRenderer(appContext) }.getOrNull().orEmpty().ifEmpty { "unknown" },
            )
            writeRaw("vulkan      : ${vulkanVersion(appContext)}")
            writeRaw("memory      : ${memoryLine(appContext)}")
            writeRaw("storage     : ${storageLine(appContext)}")
            writeRaw("app         : ${app.gamenative.BuildConfig.VERSION_NAME} (${app.gamenative.BuildConfig.VERSION_CODE})")
            if (data != null) writeConfig(data)
            writePhase("guest output")
        }.onFailure { Timber.tag(TAG).w(it, "launch log setup failed for $appId") }
    }

    private fun writeConfig(data: ContainerData) {
        writeRaw("config      : variant=${data.containerVariant} wine=${data.wineVersion} emulator=${data.emulator}")
        writeRaw("config      : driver=${data.graphicsDriver} driverVersion=${data.graphicsDriverVersion}")
        writeRaw("config      : dxwrapper=${data.dxwrapper} dxwrapperConfig=${data.dxwrapperConfig}")
        writeRaw("config      : box64=${data.box64Version}/${data.box64Preset} fexcore=${data.fexcoreVersion}/${data.fexcorePreset}")
        writeRaw("config      : videoMemorySize=${data.videoMemorySize} startup=${data.startupSelection} suspend=${data.suspendPolicy}")
        writeRaw("config      : screen=${data.screenSize} renderer=${data.renderer} audio=${data.audioDriver}")
        if (data.execArgs.isNotBlank()) writeRaw("config      : execArgs=${data.execArgs}")
        if (data.envVars.isNotBlank()) {
            // Environment values are copied for support, so credentials are masked exactly where
            // the launcher masks them for its own log line.
            writeRaw("config      : envVars=${runCatching { com.winlator.core.envvars.EnvVarRedaction.redact(data.envVars) }.getOrDefault(data.envVars)}")
        }
    }

    /**
     * Marks a phase the launcher is entering. Cheap to call repeatedly: a phase that is already the
     * current one is not written again, which keeps a retry loop from flooding the file.
     */
    fun phase(name: String) {
        if (currentAppId == null) return
        if (name == lastPhase) return
        writePhase(name)
    }

    private fun writePhase(name: String) {
        lastPhase = name
        val seconds = if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000L else 0L
        writeRaw("=== phase: $name (+${seconds}s) ===")
    }

    /**
     * One line of guest output. Lines are dropped unless a launch is open, which keeps the wine
     * callback (registered once per session and never removed) from writing into an old file.
     */
    fun append(line: String?) {
        val text = line ?: return
        if (currentAppId == null) return
        val trimmed = if (text.length > 8192) text.substring(0, 8192) else text
        writeRaw(trimmed)
    }

    /** Closes the session. Safe to call more than once, and safe to never call at all. */
    fun end(reason: String = "exit") {
        synchronized(lock) {
            if (currentAppId == null) return
            val seconds = if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000 else 0L
            writeLocked("=== session end: $reason after ${seconds}s ===")
            if (truncated) writeLocked("=== log truncated at ${MAX_BYTES / (1024 * 1024)}MB ===")
            closeLocked()
            currentAppId = null
            currentFile = null
            lastPhase = ""
        }
    }

    /** Everything written this launch, newest first, for the AI check and the report. */
    fun tail(context: Context, appId: String, maxBytes: Long = ANALYSIS_BYTES): String {
        val file = newest(context, appId) ?: return ""
        return readTail(file, maxBytes)
    }

    fun tail(file: File?, maxBytes: Long = EXPORT_BYTES): String =
        if (file == null) "" else readTail(file, maxBytes)

    /** A one-line description of a session file: its stamp, size and how it ended. */
    data class Session(val file: File, val label: String, val sizeBytes: Long, val ending: String)

    /**
     * The kept launches of [appId], newest first, with the ending line pulled from the file so the
     * viewer can show "killed by the system" next to the run it belongs to.
     */
    fun sessionList(context: Context, appId: String): List<Session> = sessions(context, appId).map { file ->
        Session(
            file = file,
            label = file.name.removePrefix("${safeId(appId)}-").removeSuffix(".log"),
            sizeBytes = file.length(),
            ending = readTail(file, 2_048).lines().lastOrNull { it.startsWith("=== session end") }.orEmpty(),
        )
    }

    /**
     * Copies the newest launch log into a shareable file in the cache dir. Null when this game has
     * no log yet. Only this game's file is copied: the export never carries other games.
     */
    suspend fun export(context: Context, appId: String, file: File? = null): File? = withContext(Dispatchers.IO) {
        try {
            val source = file ?: newest(context, appId) ?: return@withContext null
            val directory = File(context.cacheDir, "game-log-export").apply { mkdirs() }
            directory.listFiles()?.forEach { runCatching { it.delete() } }
            val name = runCatching { ContainerUtils.resolveGameName(appId) }.getOrDefault(appId)
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "game" }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val target = File(directory, "gamenative-$safeName-$stamp.log")
            source.copyTo(target, overwrite = true)
            target
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "could not export the launch log for $appId")
            null
        }
    }

    fun share(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                putExtra(Intent.EXTRA_TEXT, context.getString(R.string.game_launch_log_share_text))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.game_launch_log_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "could not share the launch log")
        }
    }

    /* ---------------------------------------------------------------- header facts */

    private fun vulkanVersion(context: Context): String = runCatching {
        val api = com.winlator.core.GPUHelper.vkGetApiVersion()
        val major = com.winlator.core.GPUHelper.vkVersionMajor(api)
        val minor = com.winlator.core.GPUHelper.vkVersionMinor(api)
        if (major <= 0 && minor <= 0) "unknown" else "$major.$minor.${api and 0xFFF}"
    }.getOrDefault("unknown")

    /** The numbers that decide whether the guest can hold a game: free RAM and the low-RAM flag. */
    private fun memoryLine(context: Context): String = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024 * 1024)
        val availMb = info.availMem / (1024 * 1024)
        "free=${availMb}MB total=${totalMb}MB lowMemory=${info.lowMemory} threshold=${info.threshold / (1024 * 1024)}MB"
    }.getOrDefault("unknown")

    private fun storageLine(context: Context): String = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeMb = stat.availableBytes / (1024 * 1024)
        "dataFree=${freeMb}MB"
    }.getOrDefault("unknown")

    private fun writeRaw(text: String) {
        synchronized(lock) { writeLocked(text) }
    }

    private fun writeLocked(text: String) {
        val out = writer ?: return
        val sink = stream
        runCatching {
            out.write(text)
            out.write("\n")
            out.flush()
            writtenBytes += text.length + 1
            // Guest output is mostly warnings and errors, and the whole point is that the last line
            // survives a native crash or a kill. Cheap enough at one sync per call.
            runCatching { sink?.fd?.sync() }
            if (writtenBytes >= MAX_BYTES && !truncated) {
                truncated = true
                out.write("=== log truncated at ${MAX_BYTES / (1024 * 1024)}MB ===")
                out.write("\n")
                out.flush()
            }
        }
    }

    private fun closeLocked() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        runCatching { stream?.close() }
        writer = null
        stream = null
    }

    private fun prune(directory: File, appId: String) {
        filesOf(directory, appId).drop(MAX_SESSIONS - 1).forEach { runCatching { it.delete() } }
    }

    private fun readTail(file: File, maxBytes: Long): String = runCatching {
        val length = file.length()
        if (length <= maxBytes) return file.readText(Charsets.UTF_8)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - maxBytes)
            val bytes = ByteArray(maxBytes.toInt())
            raf.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            val firstBreak = text.indexOf('\n')
            if (firstBreak >= 0) text.substring(firstBreak + 1) else text
        }
    }.getOrElse { "(could not read ${file.name}: ${it.message})" }
}
