package app.gamenative.utils

import app.gamenative.data.GameSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The store services and their DAOs need a live app, so these tests cover the parts the scan is
 * built from: the ACF / GOG `.info` metadata readers, the "is this really a game folder" gate, and
 * how the layout is walked. Registering a row is covered by the device check.
 */
class GameFileTakeoverTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── Steam ACF ───────────────────────────────────────────────────────────────────────

    @Test
    fun acfFields_areReadFromANormalManifest() {
        val acf = tempFolder.newFile("appmanifest_440.acf")
        acf.writeText(
            """
            "AppState"
            {
            	"appid"		"440"
            	"name"		"Team Fortress 2"
            	"StateFlags"		"4"
            	"installdir"		"Team Fortress 2"
            }
            """.trimIndent(),
        )

        assertEquals("Team Fortress 2", GameFileTakeover.readAcfField(acf, "installdir"))
        assertEquals("Team Fortress 2", GameFileTakeover.readAcfField(acf, "name"))
    }

    @Test
    fun acfInstallDir_handlesTabsAndExtraWhitespace() {
        val acf = tempFolder.newFile("appmanifest_1.acf")
        acf.writeText("\"AppState\"\n{\n\t\"installdir\"   \t \"Half-Life\"\n}\n")

        assertEquals("Half-Life", GameFileTakeover.readAcfField(acf, "installdir"))
    }

    @Test
    fun acfField_isCaseInsensitiveAndNullWhenMissing() {
        val acf = tempFolder.newFile("appmanifest_2.acf")
        acf.writeText("\"InstallDir\" \"Some Game\"\n")

        assertEquals("Some Game", GameFileTakeover.readAcfField(acf, "installdir"))
        assertNull(GameFileTakeover.readAcfField(acf, "name"))
    }

    @Test
    fun acfField_isNullForAnUnreadableManifest() {
        val acf = tempFolder.newFile("appmanifest_3.acf")
        acf.writeBytes(byteArrayOf(0, 1, 2, 3))

        // No crash, and no invented value: a manifest we cannot read is skipped.
        assertNull(GameFileTakeover.readAcfField(acf, "installdir"))
    }

    // ── GOG metadata ────────────────────────────────────────────────────────────────────

    @Test
    fun gogGameId_isReadFromTheInfoFile() {
        val dir = tempFolder.newFolder("Game")
        File(dir, "1234567890.info").writeText("""{"gameId":"1234567890","rootGameId":"1234567890"}""")

        assertEquals("1234567890", GameFileTakeover.readGogGameId(dir))
    }

    @Test
    fun gogGameId_skipsAnUnreadableInfoFile() {
        val dir = tempFolder.newFolder("Game")
        File(dir, "a.info").writeText("not json")
        File(dir, "b.info").writeText("""{"gameId":"42"}""")

        assertEquals("42", GameFileTakeover.readGogGameId(dir))
    }

    @Test
    fun gogGameId_isNullWithoutAnInfoFile() {
        val dir = tempFolder.newFolder("Game")
        File(dir, "game.exe").writeBytes(ByteArray(8))

        assertNull(GameFileTakeover.readGogGameId(dir))
    }

    // ── payload detection ───────────────────────────────────────────────────────────────

    @Test
    fun hasPayload_isFalseForAnEmptyLeftoverFolder() {
        val dir = tempFolder.newFolder("aborted-download")

        assertTrue(!GameFileTakeover.hasPayload(dir))
    }

    @Test
    fun hasPayload_findsFilesAFewLevelsDownButNotDeeper() {
        val shallow = tempFolder.newFolder("shallow")
        File(shallow, "Game.exe").writeBytes(ByteArray(4))
        assertEquals(true, GameFileTakeover.hasPayload(shallow))

        // Unreal layout: <game>/Binaries/Win64/Game-Win64-Shipping.exe
        val nested = tempFolder.newFolder("nested", "Binaries", "Win64")
        File(nested, "Game-Win64-Shipping.exe").writeBytes(ByteArray(4))
        assertEquals(true, GameFileTakeover.hasPayload(tempFolder.root.resolve("nested")))

        // Past the depth cap a stray tree is not claimed as a game.
        val deep = tempFolder.newFolder("deep")
        var cursor = deep
        repeat(6) { cursor = File(cursor, "level$it").apply { mkdirs() } }
        File(cursor, "buried.exe").writeBytes(ByteArray(4))
        assertEquals(false, GameFileTakeover.hasPayload(deep))
    }

    // ── layout walking ──────────────────────────────────────────────────────────────────

    @Test
    fun gameDirs_usesTheCommonSubfolderWhenPresent() {
        val games = tempFolder.newFolder("GOG", "games")
        val common = File(games, "common").apply { mkdirs() }
        val game = File(common, "My Game").apply { mkdirs() }
        File(game, "game.exe").writeBytes(ByteArray(4))
        // A folder next to `common` is not a game folder in this layout.
        val stray = File(games, "notes").apply { mkdirs() }
        File(stray, "readme.txt").writeText("hi")

        val dirs = GameFileTakeover.gameDirs(games)

        assertEquals(listOf(game.absolutePath), dirs.map { it.absolutePath })
    }

    @Test
    fun gameDirs_fallsBackToTheGamesFolderItself() {
        val games = tempFolder.newFolder("GOG", "games")
        val game = File(games, "Old Layout Game").apply { mkdirs() }
        File(game, "game.exe").writeBytes(ByteArray(4))

        val dirs = GameFileTakeover.gameDirs(games)

        assertEquals(listOf(game.absolutePath), dirs.map { it.absolutePath })
    }

    @Test
    fun gameDirs_isEmptyWhenTheFolderDoesNotExist() {
        assertTrue(GameFileTakeover.gameDirs(File(tempFolder.root, "nope")).isEmpty())
    }

    @Test
    fun canonical_worksForPathsThatDoNotExistYet() {
        assertNotNull(GameFileTakeover.canonical(File(tempFolder.root, "not/created/yet")))
    }

    // ── Records whose recorded folder is unreachable ─────────────────────────────────────

    /** A game registered as installed still has to be offered when its folder is gone. */
    @Test
    fun adoptable_includesRowsThatNeedTheirLocationRepaired() {
        val healthy = candidate(installed = true, stale = false)
        val needsRepair = candidate(installed = true, stale = true)
        val fresh = candidate(installed = false, stale = false)

        val result = GameFileTakeover.ScanResult(
            candidates = listOf(healthy, needsRepair, fresh),
            scannedRoots = emptyList(),
        )

        assertEquals(listOf(fresh.installPath, needsRepair.installPath), result.adoptable.map { it.installPath })
        assertEquals(listOf(healthy.installPath), result.alreadyInstalled.map { it.installPath })
        assertEquals(listOf(needsRepair.installPath), result.relocations.map { it.installPath })
    }

    @Test
    fun usableInstallDir_requiresADirectoryThatCanBeRead() {
        val dir = tempFolder.newFolder("installed")
        assertTrue(StorageUtils.isUsableInstallDir(dir.absolutePath))
        assertTrue(!StorageUtils.isUsableInstallDir(File(tempFolder.root, "missing").absolutePath))
        assertTrue(!StorageUtils.isUsableInstallDir(null))
        assertTrue(!StorageUtils.isUsableInstallDir(""))
    }

    private fun candidate(installed: Boolean, stale: Boolean) = GameFileTakeover.Candidate(
        source = GameSource.STEAM,
        containerId = "STEAM_${installed.hashCode()}_${stale.hashCode()}",
        title = "Game",
        installPath = tempFolder.root.absolutePath + "/${installed}_${stale}",
        evidence = GameFileTakeover.Evidence.STORE_METADATA,
        alreadyInstalled = installed,
        staleLocation = stale,
    )
}
