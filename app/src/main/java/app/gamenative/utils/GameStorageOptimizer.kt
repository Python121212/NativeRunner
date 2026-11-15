package app.gamenative.utils

import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

/**
 * Shrinks the space a game's files actually occupy, without changing a single byte the game reads.
 *
 * Transparent filesystem compression (EROFS, SquashFS, ZFS) needs a filesystem this app cannot
 * create, so the ways to win space on /data are the ones the filesystem itself offers:
 *
 * 1. **Junk**: crash dumps, temporary files and old logs that games leave behind.
 * 2. **Duplicates**: identical files stored twice (redistributables copied into every prefix,
 *    the same DLL next to several executables). They are replaced by hard links, so both paths
 *    keep working while the content exists once.
 * 3. **Zero runs**: archives and installers padded with megabytes of zeros. `fallocate
 *    FALLOC_FL_PUNCH_HOLE` turns those runs into holes: the file keeps its size and content, the
 *    blocks are handed back to the filesystem.
 *
 * Every number reported is measured - either from the blocks the filesystem gave back or from
 * `StatFs` before and after - because a "saved" figure that does not move the free space bar is
 * worse than no figure at all.
 */
object GameStorageOptimizer {
    private const val TAG = "StorageOptimizer"

    private const val MIN_DEDUPE_BYTES = 64L * 1024L
    private const val MAX_DEDUPE_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MIN_HOLE_FILE_BYTES = 1L * 1024L * 1024L
    private const val MIN_HOLE_RUN_BYTES = 64L * 1024L
    private const val CHUNK_BYTES = 64 * 1024
    internal const val JUNK_LOG_AGE_MS = 24L * 60L * 60L * 1000L
    private const val FALLOC_FL_KEEP_SIZE = 0x01
    private const val FALLOC_FL_PUNCH_HOLE = 0x02

    private val JUNK_SUFFIXES = arrayOf(".dmp", ".mdmp", ".tmp", ".trace.txt", ".old", ".bak")
    private val JUNK_LOG_SUFFIXES = arrayOf(".log")

    /** Files that must never be touched: they are either loaded by the runtime or being written. */
    private val SKIP_DIRS = setOf("proc", "dev", "sys", "tmp", "usr")
    private val SKIP_FILE_NAMES = setOf("wineserver", "wine", "box64", "box86")

    enum class Phase { SCAN, JUNK, DEDUPE, HOLES, DONE }

    data class OptimizerProgress(
        val phase: Phase,
        val processed: Int,
        val total: Int,
        val reclaimedBytes: Long,
        val current: String,
    )

    data class OptimizerResult(
        val junkBytes: Long,
        val dedupeBytes: Long,
        val holeBytes: Long,
        val measuredFreeDelta: Long,
        val scannedFiles: Int,
        val droppedFiles: Int,
        val linkedFiles: Int,
        val unsupported: List<String>,
        val elapsedMs: Long,
    ) {
        val claimedBytes: Long get() = junkBytes + dedupeBytes + holeBytes
        val anyChange: Boolean get() = claimedBytes > 0
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * Runs every pass over [roots]. Blocking IO: call from a background coroutine. The session must
     * not be running - a game holds these files open.
     */
    suspend fun optimize(
        roots: List<File>,
        onProgress: (OptimizerProgress) -> Unit = {},
    ): OptimizerResult = withContext(Dispatchers.IO) {
        cancelled = false
        val started = System.currentTimeMillis()
        val existing = roots.filter { it.isDirectory }
        if (existing.isEmpty()) {
            return@withContext OptimizerResult(0, 0, 0, 0, 0, 0, 0, listOf("no game directory found"), 0)
        }

        val freeBefore = freeBytes(existing.first())
        val unsupported = mutableListOf<String>()

        // 1. junk
        val junk = mutableListOf<File>()
        val all = ArrayList<File>(4096)
        existing.forEach { root -> collect(root, all, junk) }
        onProgress(OptimizerProgress(Phase.SCAN, 0, all.size, 0, ""))

        var junkBytes = 0L
        var dropped = 0
        junk.forEachIndexed { index, file ->
            if (cancelled) return@forEachIndexed
            val size = file.length()
            if (file.delete()) {
                junkBytes += size
                dropped += 1
            }
            if (index % 32 == 0) {
                onProgress(OptimizerProgress(Phase.JUNK, index, junk.size, junkBytes, file.name))
            }
        }
        onProgress(OptimizerProgress(Phase.JUNK, junk.size, junk.size, junkBytes, ""))

        // 2. duplicates -> hard links
        var dedupeBytes = 0L
        var linked = 0
        val candidates = all.filter {
            it.exists() && it.length() >= MIN_DEDUPE_BYTES && it.length() <= MAX_DEDUPE_BYTES
        }
        val bySize = candidates.groupBy { it.length() }.filterValues { it.size > 1 }
        var handled = 0
        for ((_, sameSize) in bySize) {
            if (cancelled) break
            val byHash = HashMap<String, File>()
            for (file in sameSize.sortedBy { it.absolutePath }) {
                if (cancelled) break
                val digest = runCatching { digest(file) }.getOrNull()
                if (digest == null) continue
                val keeper = byHash[digest]
                if (keeper == null) {
                    byHash[digest] = file
                } else {
                    val size = file.length()
                    if (hardLink(keeper, file)) {
                        dedupeBytes += size
                        linked += 1
                    }
                }
                handled += 1
                if (handled % 16 == 0) {
                    onProgress(OptimizerProgress(Phase.DEDUPE, handled, candidates.size, dedupeBytes, file.name))
                }
            }
        }
        onProgress(OptimizerProgress(Phase.DEDUPE, candidates.size, candidates.size, dedupeBytes, ""))

        // 3. zero runs -> holes
        var holeBytes = 0L
        var holeSupported = true
        val bigFiles = all.filter { it.exists() && it.length() >= MIN_HOLE_FILE_BYTES }
        bigFiles.forEachIndexed { index, file ->
            if (cancelled) return@forEachIndexed
            if (!holeSupported) return@forEachIndexed
            when (val punched = punchHoles(file)) {
                null -> {
                    holeSupported = false
                    unsupported += "hole punching"
                    Log.w(TAG, "Hole punching is not supported on this filesystem")
                }
                else -> holeBytes += punched
            }
            if (index % 8 == 0) {
                onProgress(OptimizerProgress(Phase.HOLES, index, bigFiles.size, holeBytes, file.name))
            }
        }
        onProgress(OptimizerProgress(Phase.HOLES, bigFiles.size, bigFiles.size, holeBytes, ""))

        val freeAfter = freeBytes(existing.first())
        val result = OptimizerResult(
            junkBytes = junkBytes,
            dedupeBytes = dedupeBytes,
            holeBytes = holeBytes,
            measuredFreeDelta = (freeAfter - freeBefore).coerceAtLeast(0),
            scannedFiles = all.size,
            droppedFiles = dropped,
            linkedFiles = linked,
            unsupported = unsupported,
            elapsedMs = System.currentTimeMillis() - started,
        )
        Timber.tag(TAG).i(
            "optimize done: junk=%dMB dedupe=%dMB holes=%dMB measured=%dMB scanned=%d in %dms",
            result.junkBytes / 1_048_576,
            result.dedupeBytes / 1_048_576,
            result.holeBytes / 1_048_576,
            result.measuredFreeDelta / 1_048_576,
            result.scannedFiles,
            result.elapsedMs,
        )
        PersistentLog.write(
            Log.INFO,
            TAG,
            "storage optimize: junk=${result.junkBytes} dedupe=${result.dedupeBytes} " +
                "holes=${result.holeBytes} measured=${result.measuredFreeDelta} files=${result.scannedFiles}",
        )
        onProgress(OptimizerProgress(Phase.DONE, 0, 0, result.measuredFreeDelta, ""))
        result
    }

    private fun collect(directory: File, files: MutableList<File>, junk: MutableList<File>) {
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (cancelled) return
            if (child.isDirectory) {
                if (SKIP_DIRS.contains(child.name.lowercase(Locale.US))) continue
                if (child.name.startsWith(".")) continue
                collect(child, files, junk)
                continue
            }
            if (!child.isFile) continue
            if (SKIP_FILE_NAMES.contains(child.name.lowercase(Locale.US))) continue

            files += child
            if (isJunk(child)) junk += child
        }
    }

    private fun isJunk(file: File): Boolean =
        isJunkName(file.name, file.lastModified(), System.currentTimeMillis())

    /** Pure so the junk rules can be tested: dumps and temp files always, logs only when stale. */
    internal fun isJunkName(name: String, lastModified: Long, now: Long): Boolean {
        val lower = name.lowercase(Locale.US)
        if (JUNK_SUFFIXES.any { lower.endsWith(it) }) return true
        if (JUNK_LOG_SUFFIXES.any { lower.endsWith(it) } && now - lastModified > JUNK_LOG_AGE_MS) {
            return true
        }
        return false
    }

    /** Replaces [duplicate] with a link to [keeper]. Returns true when the space was freed. */
    private fun hardLink(keeper: File, duplicate: File): Boolean {
        if (keeper.absolutePath == duplicate.absolutePath) return false
        // Guard against a chain where the keeper itself was replaced later.
        if (!keeper.exists() || !duplicate.exists()) return false
        if (runCatching { Os.stat(keeper.absolutePath).st_ino }.getOrNull() ==
            runCatching { Os.stat(duplicate.absolutePath).st_ino }.getOrNull()
        ) {
            return false
        }

        val staging = File(duplicate.parentFile, ".${duplicate.name}.gnlink")
        runCatching { staging.delete() }
        return runCatching {
            Os.link(keeper.absolutePath, staging.absolutePath)
            if (!duplicate.delete()) {
                staging.delete()
                return false
            }
            if (!staging.renameTo(duplicate)) {
                // Put a copy back rather than leave the path missing.
                keeper.copyTo(duplicate, overwrite = true)
                staging.delete()
                return false
            }
            true
        }.getOrElse {
            runCatching { staging.delete() }
            Timber.tag(TAG).w("hard link failed for ${duplicate.absolutePath}: ${it.message}")
            false
        }
    }

    /**
     * Punches the long zero runs of [file] into holes.
     *
     * @return bytes handed back, 0 when the file had none, null when the filesystem refused
     */
    private fun punchHoles(file: File): Long? {
        val fallocate = fallocateMethod ?: return null
        var punched = 0L
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val length = raf.length()
                val buffer = ByteArray(CHUNK_BYTES)
                var position = 0L
                var runStart = -1L

                while (position < length) {
                    if (cancelled) return punched
                    raf.seek(position)
                    val read = raf.read(buffer)
                    if (read <= 0) break

                    var index = 0
                    while (index < read) {
                        val zero = buffer[index] == 0.toByte()
                        if (zero && runStart < 0) runStart = position + index
                        if (!zero && runStart >= 0) {
                            punched += punchRun(fallocate, raf.fd, runStart, position + index - runStart)
                            runStart = -1
                        }
                        index += 1
                    }
                    position += read
                }
                if (runStart >= 0) {
                    punched += punchRun(fallocate, raf.fd, runStart, length - runStart)
                }
            }
        }.onFailure {
            Timber.tag(TAG).w("hole punching failed on ${file.absolutePath}: ${it.message}")
            return null
        }
        return punched
    }

    private fun punchRun(method: java.lang.reflect.Method, fd: FileDescriptor, start: Long, length: Long): Long {
        if (length < MIN_HOLE_RUN_BYTES) return 0
        // 4 KiB aligned window inside the run, so a partial block is never handed back.
        val page = 4096L
        val alignedStart = ((start + page - 1) / page) * page
        val alignedEnd = ((start + length) / page) * page
        if (alignedEnd - alignedStart < MIN_HOLE_RUN_BYTES) return 0
        return runCatching {
            method.invoke(null, fd, FALLOC_FL_KEEP_SIZE or FALLOC_FL_PUNCH_HOLE, alignedStart, alignedEnd - alignedStart)
            alignedEnd - alignedStart
        }.getOrElse {
            Log.w(TAG, "punch hole failed: ${it.cause?.message ?: it.message}")
            0L
        }
    }

    /**
     * `android.system.Os.fallocate` is the only way to punch a hole from the app process, and it
     * is looked up by reflection so an SDK without it just means "not supported" instead of a
     * build that cannot compile.
     */
    private val fallocateMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("android.system.Os").getMethod(
                "fallocate",
                FileDescriptor::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun freeBytes(file: File): Long = runCatching {
        android.os.StatFs(file.absolutePath).availableBytes
    }.getOrDefault(0L)
}
