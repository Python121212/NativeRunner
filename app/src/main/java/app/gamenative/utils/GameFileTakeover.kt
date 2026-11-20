package app.gamenative.utils

import android.content.Context
import android.os.Environment
import app.gamenative.PrefManager
import app.gamenative.data.AppInfo
import app.gamenative.data.GameSource
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.enums.Marker
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import java.io.File
import java.nio.file.Paths
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Finds game files that are already on the device but were never registered in this build, and
 * registers them.
 *
 * The usual cause is switching builds: the official GameNative and a self-signed build share the
 * `app.gamenative` application id but not the signing key, so installing one replaces the other's
 * **private data** (DB, preferences, containers) while the multi-gigabyte game folders sit in
 * shared storage and are never looked at again. The library then shows every store game as "not
 * installed" and the next download fetches all of it a second time.
 *
 * This object only ever *registers* what is already on disk. It never moves, deletes or rewrites a
 * game folder, and it never touches a game a store has already claimed: for every candidate the
 * store DB is consulted first and an already-installed row always wins.
 *
 * The one exception is a row that claims to be installed while its recorded folder is unreachable
 * (another application's private storage): that row is *repointed* at the folder found here,
 * because otherwise the game shows as installed forever and can never start. Files are still not
 * touched.
 *
 * Layouts it understands — all of them created by GameNative itself:
 *  - Steam:  `<root>/Steam/steamapps/common/<installdir>` (+ `appmanifest_<id>.acf`)
 *  - GOG:    `<root>/GOG/games/common/<title>`, older builds `<root>/GOG/games/<title>`
 *  - Epic:   `<root>/Epic/games/<title>`
 *  - Amazon: `<root>/Amazon/games/<title>`
 * where `<root>` is the internal data dir, the configured external root, any mounted volume, or
 * the per-app `Android/data/app.gamenative/files` directory and the public `GameNative` folder of
 * the official build (NativeRunner keeps its own games in `NativeRunner/`).
 */
object GameFileTakeover {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TakeoverEntryPoint {
        fun appInfoDao(): AppInfoDao
    }

    /** How a candidate was recognised; shown in the dialog so nothing is taken on trust. */
    enum class Evidence {
        /** The store's own metadata file: Steam ACF, GOG `.info`, Epic `.gamenative_epic_install`. */
        STORE_METADATA,

        /** The folder name matches exactly one library entry. */
        NAME_MATCH,
    }

    data class Candidate(
        val source: GameSource,
        val containerId: String,
        val title: String,
        val installPath: String,
        val evidence: Evidence,
        /** Already registered as installed: shown for context, never re-written. */
        val alreadyInstalled: Boolean = false,
        val sizeBytes: Long = 0L,
        /**
         * The row says "installed", but the folder it records cannot be used by this app.
         *
         * This is the case that looked fine and then failed: an install left an "installed" row
         * whose recorded path is another application's private data (typically the official
         * `app.gamenative`, unreadable once this build runs as `app.nativerunner`). Such a row
         * reports "installed" forever while the game never starts, so the takeover repoints it at
         * the folder it actually found and counts the candidate as adoptable.
         */
        val staleLocation: Boolean = false,
    )

    /**
     * A DB row that says "installed" while its folder is unreachable *and* no replacement folder
     * was found. Nothing can be registered for it — the data belongs to another app's private
     * storage — so the dialog explains it instead of returning a bare "nothing found".
     */
    data class Unreachable(
        val appId: Int,
        val title: String,
        val recordedPath: String,
    )

    data class ScanResult(
        val candidates: List<Candidate>,
        val scannedRoots: List<String>,
        val errors: List<String> = emptyList(),
        /**
         * Locations that exist but cannot be read from this app (another application's private
         * data). Their contents are unreachable, so nothing in them can be registered; they are
         * surfaced so the user is told the game is still on the device instead of seeing an
         * unexplained empty result.
         */
        val unreadableRoots: List<String> = emptyList(),
        /** Installed rows whose folders are unreachable and were not found anywhere else. */
        val unreachable: List<Unreachable> = emptyList(),
    ) {
        /** Rows to write: not installed yet, or installed at a location that is gone. */
        val adoptable: List<Candidate>
            get() = candidates.filter { !it.alreadyInstalled || it.staleLocation }

        val alreadyInstalled: List<Candidate>
            get() = candidates.filter { it.alreadyInstalled && !it.staleLocation }

        /** Installed rows that only need their recorded location corrected. */
        val relocations: List<Candidate>
            get() = candidates.filter { it.alreadyInstalled && it.staleLocation }
    }

    /**
     * Version of the set of install locations this build understands.
     *
     * Bumped when the layout changes so an automatic repair pass runs once more even if the user
     * dismissed the start-up prompt:
     * - 1 = after the application id split (records that still point into `app.gamenative`'s
     *   private storage are repointed).
     * - 2 = the scan now also walks `<volume>/GameNative` and the official app-specific folder, so
     *   a pass that previously found "0 candidates" is worth repeating.
     */
    const val LAYOUT_REPAIR_VERSION = 2

    // Steam's shared depot lives under steamapps/common too; it is not a game.
    private const val STEAMWORKS_COMMON_APP_ID = 228980
    private const val STEAMWORKS_COMMON_DIR = "Steamworks Shared"

    /**
     * Every location worth walking, in priority order and de-duplicated by canonical path.
     *
     * This list is deliberately *not* derived from settings. The earlier version only added the
     * configured install root and whatever [StorageUtils.getAllExternalFilesDirs] reported, which
     * on a device that keeps games in internal storage produced three roots — all inside this
     * app's own folder. The official build's `GameNative/` (public storage) and its app-specific
     * folder were therefore never visited, the scan reported "0 candidates", and the games looked
     * absent even though they were on the device.
     */
    fun scanRootCandidates(context: Context): List<File> {
        val roots = LinkedHashSet<String>()

        fun add(path: String?) {
            if (path.isNullOrBlank()) return
            val real = runCatching { File(path).canonicalPath }.getOrDefault(path)
            roots.add(real)
        }

        // 1. This app: internal data dir, then whatever the download service initialised.
        add(context.dataDir?.path)
        add(DownloadService.baseDataDirPath.takeIf { it.isNotBlank() })
        add(context.getExternalFilesDir(null)?.absolutePath)
        add(DownloadService.baseExternalAppDirPath.takeIf { it.isNotBlank() })
        if (PrefManager.useExternalStorage) add(PrefManager.externalStoragePath)
        DownloadService.externalVolumePaths.forEach { add(it) }

        // 2. Every volume, independent of settings and of how many the enumeration reports.
        val volumeRoots = LinkedHashSet<String>()
        // /storage/emulated/<user> — the shared storage the user sees in a file manager. Absent
        // from getExternalFilesDirs() on some releases, which is exactly how the official build's
        // public folder slipped through.
        runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()?.let {
            volumeRoots.add(it)
        }
        runCatching { context.getExternalFilesDirs(null)?.filterNotNull().orEmpty() }.getOrDefault(emptyList())
            .forEach { StorageUtils.volumeRootOf(it)?.let(volumeRoots::add) }
        StorageUtils.getAllExternalFilesDirs(context)
            .forEach { StorageUtils.volumeRootOf(it)?.let(volumeRoots::add) }

        volumeRoots.forEach { volume ->
            val root = File(volume)
            add(root.absolutePath)
            // <volume>/NativeRunner (ours) and <volume>/GameNative (official, read-only).
            StorageUtils.publicInstallRootsFor(root).forEach { add(it.absolutePath) }
            // The official build's app-specific folder on this volume. Its private data on the
            // primary volume cannot be read from here, but the folder itself may exist and is
            // reported so the dialog can say *why* it was skipped.
            add("$volume/Android/data/${AppIdentity.OFFICIAL_PACKAGE}/files")
            add("$volume/Android/data/${AppIdentity.OFFICIAL_PACKAGE}")
        }

        // 3. The official build's private data on the internal volume. Unreadable from this
        //    process by design (mode 0700, different UID); listed so the scan can tell the user
        //    that the game it remembers is there and need not be re-downloaded.
        add(officialPrivateDataDir(context)?.absolutePath)

        return roots.map { File(it) }
    }

    /**
     * Directories that exist but cannot be read from this app — another application's private
     * data. Walking them yields nothing, so the scan reports them instead of silently skipping.
     */
    fun unreadableRoots(roots: List<File>): List<File> =
        roots.filter { it.isDirectory && !it.canRead() }

    /** Directories to walk: exists and readable. */
    fun scanRoots(context: Context): List<File> =
        scanRootCandidates(context).filter { it.isDirectory && it.canRead() }

    /** `/data/user/<user>/<official package>` for the current user, if it exists. */
    private fun officialPrivateDataDir(context: Context): File? {
        val candidates = listOfNotNull(
            context.dataDir?.parentFile?.let { File(it, AppIdentity.OFFICIAL_PACKAGE) },
            File("/data/user/${currentUserId()}/${AppIdentity.OFFICIAL_PACKAGE}"),
            File("/data/data/${AppIdentity.OFFICIAL_PACKAGE}"),
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun currentUserId(): Int = runCatching {
        android.os.Process.myUid() / 100000
    }.getOrDefault(0)

    /**
     * Walks the roots and reports what is on disk. Only directory entries and the stores' small
     * metadata files are read here; game payloads are measured but never opened.
     */
    suspend fun scan(
        context: Context,
        onProgress: ((done: Int, total: Int, label: String) -> Unit)? = null,
    ): ScanResult = withContext(Dispatchers.IO) {
        val roots = scanRoots(context)
        val errors = mutableListOf<String>()
        val found = LinkedHashMap<String, Candidate>()

        fun addCandidate(candidate: Candidate) {
            val key = runCatching { File(candidate.installPath).canonicalPath }
                .getOrDefault(candidate.installPath)
            val existing = found[key]
            // One entry per real path; a metadata-backed hit outranks a name-only guess.
            if (existing == null ||
                (existing.evidence == Evidence.NAME_MATCH && candidate.evidence != Evidence.NAME_MATCH)
            ) {
                found[key] = candidate
            }
        }

        // Installed Steam rows, read straight from the DB: SteamService (and its DAO) only exists
        // once Steam has started, but a stale install record must be fixable before login too.
        val installedRows: Map<Int, AppInfo> = runCatching {
            installedSteamRows(context).associateBy { it.id }
        }.getOrDefault(emptyMap())

        // GOG/Epic/Amazon read their library rows, so the steps are suspend lambdas.
        val steps = listOf<Pair<String, suspend (File) -> Unit>>(
            "Steam" to { root -> scanSteam(root, ::addCandidate, errors, installedRows) },
            "GOG" to { root -> scanGog(root, ::addCandidate) },
            "Epic" to { root -> scanEpic(root, ::addCandidate) },
            "Amazon" to { root -> scanAmazon(root, ::addCandidate) },
        )

        var done = 0
        val total = roots.size * steps.size
        for (root in roots) {
            for ((label, step) in steps) {
                onProgress?.invoke(done, total, "$label — ${root.name}")
                try {
                    step(root)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Takeover scan failed for %s in %s", label, root.absolutePath)
                    errors.add("$label: ${root.absolutePath}")
                }
                done++
            }
        }

        val unreadable = unreadableRoots(scanRootCandidates(context))
            .map { it.absolutePath }

        val sized = found.values
            .map { it.copy(sizeBytes = directorySize(it.installPath)) }
            .sortedWith(compareBy({ it.source.ordinal }, { it.title.lowercase() }))

        // A stale row that no folder on disk can satisfy: the data sits in the other app's
        // private storage, which this process cannot read at any privilege. Worth naming, even
        // though there is nothing to register.
        val unreachable = installedRows.values
            .filter { isStaleLocation(it) }
            .filter { !isStaleCoveredByFolder(it, sized) }
            .map {
                Unreachable(
                    appId = it.id,
                    // Prefer the store's own name; the folder on disk is the fallback because the
                    // scan may run before Steam has started, when the app table is not loaded.
                    title = runCatching { SteamService.getAppInfoOf(it.id)?.name }
                        .getOrNull()
                        ?.takeIf { name -> name.isNotBlank() }
                        ?: File(it.customInstallPath).name.ifBlank { it.id.toString() },
                    recordedPath = it.customInstallPath,
                )
            }
            .sortedBy { it.title.lowercase() }

        ScanResult(sized, roots.map { it.absolutePath }, errors, unreadable, unreachable)
    }

    /**
     * Registers [candidates] and returns the ones actually written.
     *
     * [context] is only used to reach the database: rows must be writable before Steam has
     * started, which is exactly when a broken install record is noticed.
     */
    suspend fun adopt(
        context: Context,
        candidates: List<Candidate>,
        onProgress: ((done: Int, total: Int, label: String) -> Unit)? = null,
    ): List<Candidate> = withContext(Dispatchers.IO) {
        val adopted = mutableListOf<Candidate>()
        val appInfoDao = EntryPointAccessors
            .fromApplication(context.applicationContext, TakeoverEntryPoint::class.java)
            .appInfoDao()
        candidates.forEachIndexed { index, candidate ->
            onProgress?.invoke(index, candidates.size, candidate.title)
            val ok = try {
                register(appInfoDao, candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not adopt %s", candidate.installPath)
                false
            }
            if (ok) {
                // The stores key "installed" off these markers; without the completion marker
                // every launch treats the folder as a partial download and offers to restart it.
                // Writing into the official build's folder may fail (its data is owned by a
                // different UID), and that must not undo a registration that already succeeded:
                // the row is what matters, the marker is a hint.
                runCatching {
                    MarkerUtils.addMarker(candidate.installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    MarkerUtils.removeMarker(candidate.installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                }.onFailure {
                    Timber.w(it, "Takeover: markers not writable in %s (registered anyway)", candidate.installPath)
                }
                adopted.add(candidate)
            }
        }
        if (adopted.isNotEmpty()) PrefManager.gameFileTakeoverLastRun = System.currentTimeMillis()
        adopted
    }

    private suspend fun register(dao: AppInfoDao, candidate: Candidate): Boolean = when (candidate.source) {
        GameSource.STEAM -> adoptSteam(dao, candidate)
        GameSource.GOG -> adoptGog(candidate)
        GameSource.EPIC -> adoptEpic(candidate)
        GameSource.AMAZON -> adoptAmazon(candidate)
        GameSource.CUSTOM_GAME -> adoptCustomGame(candidate)
    }

    private suspend fun installedSteamRows(context: Context): List<AppInfo> =
        EntryPointAccessors
            .fromApplication(context.applicationContext, TakeoverEntryPoint::class.java)
            .appInfoDao()
            .getAll()
            .filter { it.isDownloaded }

    // ── Steam ─────────────────────────────────────────────────────────────────────────────

    private fun scanSteam(
        root: File,
        add: (Candidate) -> Unit,
        errors: MutableList<String>,
        installedRows: Map<Int, AppInfo>,
    ) {
        val steamapps = File(root, "Steam/steamapps")
        val common = File(steamapps, "common")
        if (!common.isDirectory) return

        val claimed = mutableSetOf<String>()
        steamapps.listFiles { f -> f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") }
            .orEmpty()
            .forEach { file ->
                val appId = file.name.removePrefix("appmanifest_").removeSuffix(".acf").toIntOrNull()
                    ?: return@forEach
                if (appId == STEAMWORKS_COMMON_APP_ID) return@forEach
                val installDir = readAcfField(file, "installdir") ?: return@forEach
                val dir = File(common, installDir)
                if (!dir.isDirectory) return@forEach
                claimed.add(canonical(dir))
                add(
                    steamCandidate(
                        appId = appId,
                        dir = dir,
                        title = readAcfField(file, "name") ?: installDir,
                        evidence = Evidence.STORE_METADATA,
                        row = installedRows[appId],
                    ),
                )
            }

        // No ACF (a build that never wrote one, or a folder copied in by hand): fall back to
        // matching the folder name against the Steam library.
        common.listFiles { f -> f.isDirectory && f.name != STEAMWORKS_COMMON_DIR }
            .orEmpty()
            .forEach { dir ->
                if (canonical(dir) in claimed) return@forEach
                val matches = SteamService.findSteamAppWithInstallDir(dir.name)
                    .orEmpty()
                    .filter { it.id != STEAMWORKS_COMMON_APP_ID }
                when (matches.size) {
                    0 -> Unit
                    1 -> add(
                        steamCandidate(
                            appId = matches[0].id,
                            dir = dir,
                            title = matches[0].name,
                            evidence = Evidence.NAME_MATCH,
                            row = installedRows[matches[0].id],
                        ),
                    )
                    // Ambiguous: picking one would attach another game's container to the files.
                    else -> errors.add("Steam: '${dir.name}' は複数のアプリに一致するため除外しました")
                }
            }
    }

    /**
     * True when a folder found by the scan belongs to [row] even though the row's recorded path
     * is unreadable — then the row is a repair candidate, not an unreachable one.
     */
    private fun isStaleCoveredByFolder(row: AppInfo, found: List<Candidate>): Boolean {
        val recorded = row.customInstallPath
        if (recorded.isBlank()) return false
        return found.any { candidate ->
            candidate.source == GameSource.STEAM &&
                (candidate.installPath == recorded || canonical(File(candidate.installPath)) == canonical(File(recorded)))
        }
    }

    /**
     * True when the stored row claims the game is installed but its recorded folder is one this
     * app cannot reach — typically another application's private data, e.g. the official build's
     * `/data/user/<n>/app.gamenative/...`. Such a row reports "installed" forever while the game
     * never starts, so the takeover has to repoint it.
     */
    private fun isStaleLocation(row: AppInfo?): Boolean {
        if (row == null || !row.isDownloaded) return false
        val recorded = row.customInstallPath.takeIf { it.isNotBlank() } ?: return false
        return !StorageUtils.isUsableInstallDir(recorded)
    }

    private fun steamCandidate(
        appId: Int,
        dir: File,
        title: String,
        evidence: Evidence,
        row: AppInfo?,
    ) = Candidate(
        source = GameSource.STEAM,
        containerId = "${GameSource.STEAM.containerPrefix}$appId",
        title = title.ifBlank { dir.name },
        installPath = dir.absolutePath,
        evidence = evidence,
        alreadyInstalled = row?.isDownloaded == true,
        staleLocation = isStaleLocation(row),
    )

    private suspend fun adoptSteam(dao: AppInfoDao, candidate: Candidate): Boolean {
        val appId = candidate.containerId.removePrefix(GameSource.STEAM.containerPrefix).toIntOrNull()
            ?: return false
        val existing = dao.getInstalledApp(appId)
        if (existing != null && existing.isDownloaded) {
            if (!candidate.staleLocation) return false
            // Keep the row (depots, branch, play-time-independent metadata) and only move the
            // recorded location to the folder that actually exists.
            dao.insert(
                existing.copy(
                    customInstallPath = candidate.installPath,
                    recoveredInstallSizeBytes = candidate.sizeBytes,
                ),
            )
            Timber.i(
                "Takeover: repointed Steam app %s from an unreadable location to %s",
                appId,
                candidate.installPath,
            )
            return true
        }

        // Depot ids come from the store, so the row matches what a real download writes and the
        // existing files are recognised as the right depots rather than an unknown layout.
        val depots = runCatching {
            SteamService.getDownloadableDepots(appId, PrefManager.containerLanguage)
        }.getOrDefault(emptyMap())
            .filterValues { it.dlcAppId == SteamService.INVALID_APP_ID }
            .keys
            .sorted()

        dao.insert(
            AppInfo(
                id = appId,
                isDownloaded = true,
                downloadedDepots = depots,
                dlcDepots = emptyList(),
                branch = "public",
                customInstallPath = "",
            ),
        )
        Timber.i("Takeover: registered Steam app %s at %s", appId, candidate.installPath)
        return true
    }

    // ── GOG ───────────────────────────────────────────────────────────────────────────────

    private suspend fun scanGog(root: File, add: (Candidate) -> Unit) {
        gameDirs(File(root, "GOG/games")).forEach { dir ->
            // GOG writes `<gameId>.info` next to the payload; the id is the DB key.
            val gameId = readGogGameId(dir)
            val known = gameId?.let {
                runCatching { GOGService.getInstance()?.gogManager?.getGameFromDbById(it) }.getOrNull()
            }
            add(
                Candidate(
                    source = GameSource.GOG,
                    containerId = "${GameSource.GOG.containerPrefix}${gameId ?: dir.name}",
                    title = known?.title?.ifBlank { dir.name } ?: dir.name,
                    installPath = dir.absolutePath,
                    evidence = if (gameId != null) Evidence.STORE_METADATA else Evidence.NAME_MATCH,
                    alreadyInstalled = known?.isInstalled == true,
                    staleLocation = known?.isInstalled == true &&
                        !StorageUtils.isUsableInstallDir(known.installPath),
                ),
            )
        }
    }

    internal fun readGogGameId(dir: File): String? =
        dir.listFiles { f -> f.isFile && f.extension == "info" }
            .orEmpty()
            .sortedBy { it.name }
            .firstNotNullOfOrNull { file ->
                runCatching { JSONObject(file.readText()).optString("gameId").takeIf { it.isNotBlank() } }
                    .getOrNull()
            }

    private suspend fun adoptGog(candidate: Candidate): Boolean {
        val manager = GOGService.getInstance()?.gogManager ?: return false
        val gameId = candidate.containerId.removePrefix(GameSource.GOG.containerPrefix)
        val existing = manager.getGameFromDbById(gameId) ?: return false
        // Already installed where the folder really is: nothing to do. Installed at a location
        // this app cannot read (another app's private data) is repointed below.
        if (existing.isInstalled && !candidate.staleLocation) return false
        manager.updateInstallState(
            gameId,
            isInstalled = true,
            installPath = candidate.installPath,
            installSize = candidate.sizeBytes,
        )
        Timber.i("Takeover: registered GOG game %s at %s", gameId, candidate.installPath)
        return true
    }

    // ── Epic ──────────────────────────────────────────────────────────────────────────────

    private suspend fun scanEpic(root: File, add: (Candidate) -> Unit) {
        val games = runCatching { EpicService.getInstance()?.epicManager?.getAllGames() }
            .getOrNull()
            .orEmpty()
        gameDirs(File(root, "Epic/games")).forEach { dir ->
            // The install-state file proves this build wrote the folder; matching it to a
            // library row is by title, since Epic's DB keys on the generated int id.
            val hasState = File(dir, ".gamenative_epic_install.json").isFile
            val known = games.firstOrNull { game ->
                game.title.isNotBlank() && dir.name.equals(game.title, ignoreCase = true)
            } ?: games.firstOrNull { game ->
                game.installPath.isNotBlank() && canonical(File(game.installPath)) == canonical(dir)
            }
            add(
                Candidate(
                    source = GameSource.EPIC,
                    containerId = "${GameSource.EPIC.containerPrefix}${known?.id ?: dir.name}",
                    title = known?.title?.ifBlank { dir.name } ?: dir.name,
                    installPath = dir.absolutePath,
                    evidence = if (hasState) Evidence.STORE_METADATA else Evidence.NAME_MATCH,
                    alreadyInstalled = known?.isInstalled == true,
                    staleLocation = known?.isInstalled == true &&
                        !StorageUtils.isUsableInstallDir(known.installPath),
                ),
            )
        }
    }

    private suspend fun adoptEpic(candidate: Candidate): Boolean {
        val manager = EpicService.getInstance()?.epicManager ?: return false
        val appId = candidate.containerId.removePrefix(GameSource.EPIC.containerPrefix).toIntOrNull()
            ?: return false
        val game = manager.getGameById(appId) ?: return false
        if (game.isInstalled && !candidate.staleLocation) return false
        manager.updateGame(game.copy(isInstalled = true, installPath = candidate.installPath))
        Timber.i("Takeover: registered Epic game %s at %s", appId, candidate.installPath)
        return true
    }

    // ── Amazon ────────────────────────────────────────────────────────────────────────────

    private suspend fun scanAmazon(root: File, add: (Candidate) -> Unit) {
        val games = runCatching { AmazonService.getInstance()?.amazonManager?.getAllGames() }
            .getOrNull()
            .orEmpty()
        gameDirs(File(root, "Amazon/games")).forEach { dir ->
            val known = games.firstOrNull { game ->
                game.title.isNotBlank() && dir.name.equals(game.title, ignoreCase = true)
            } ?: games.firstOrNull { game ->
                game.installPath.isNotBlank() && canonical(File(game.installPath)) == canonical(dir)
            }
            add(
                Candidate(
                    source = GameSource.AMAZON,
                    containerId = "${GameSource.AMAZON.containerPrefix}${known?.productId ?: dir.name}",
                    title = known?.title?.ifBlank { dir.name } ?: dir.name,
                    installPath = dir.absolutePath,
                    evidence = Evidence.NAME_MATCH,
                    alreadyInstalled = known?.isInstalled == true,
                    staleLocation = known?.isInstalled == true &&
                        !StorageUtils.isUsableInstallDir(known.installPath),
                ),
            )
        }
    }

    private suspend fun adoptAmazon(candidate: Candidate): Boolean {
        val manager = AmazonService.getInstance()?.amazonManager ?: return false
        val productId = candidate.containerId.removePrefix(GameSource.AMAZON.containerPrefix)
        manager.markInstalled(productId, candidate.installPath, candidate.sizeBytes)
        Timber.i("Takeover: registered Amazon game %s at %s", productId, candidate.installPath)
        return true
    }

    // ── Custom games ──────────────────────────────────────────────────────────────────────

    private fun adoptCustomGame(candidate: Candidate): Boolean {
        val folders = PrefManager.customGameManualFolders.toMutableSet()
        if (!folders.add(candidate.installPath)) return false
        PrefManager.customGameManualFolders = folders
        CustomGameScanner.invalidateCache()
        Timber.i("Takeover: registered custom game folder %s", candidate.installPath)
        return true
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    /** `<root>/GOG/games/common` holds payloads; older layouts put them directly in `games/`. */
    internal fun gameDirs(gamesParent: File): List<File> {
        if (!gamesParent.isDirectory) return emptyList()
        val common = File(gamesParent, "common")
        val base = if (common.isDirectory) common else gamesParent
        return base.listFiles { f -> f.isDirectory }
            .orEmpty()
            .filter { it.name != "common" }
            // An aborted download can leave an empty folder; that is not a game.
            .filter { hasPayload(it) }
    }

    /** Walks at most four levels looking for any file, so a deep game tree is not traversed. */
    internal fun hasPayload(dir: File, depth: Int = 0): Boolean {
        if (depth > 3) return false
        val children = dir.listFiles().orEmpty()
        if (children.any { it.isFile }) return true
        return children.any { it.isDirectory && hasPayload(it, depth + 1) }
    }

    internal fun canonical(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun directorySize(path: String): Long =
        runCatching { StorageUtils.directorySizeOnDisk(Paths.get(path)) }.getOrDefault(0L)

    /**
     * ACF is Valve KeyValues text; the two fields used here sit directly under `AppState`. Parsed
     * by hand rather than through the SteamKit parser so a half-written manifest cannot throw.
     */
    internal fun readAcfField(file: File, field: String): String? = runCatching {
        Regex("\"${Regex.escape(field)}\"\\s+\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(file.readText())
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
