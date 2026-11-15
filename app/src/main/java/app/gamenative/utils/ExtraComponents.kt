package app.gamenative.utils

import com.winlator.contents.ContentProfile

/**
 * Components this build offers that the upstream manifest does not list.
 *
 * The interesting one is DXVK for ARM64EC Wine. A Bionic container runs an arm64ec Wine, so the
 * 64-bit Direct3D DLLs must be arm64ec code: the x86_64 DXVK from the bundled list is a PE the
 * arm64ec loader cannot map, and the session silently falls back to Wine's builtin d3d11 - which
 * on a Mali device shows up as "a D3D11-compatible GPU is required". These are unmodified upstream
 * DXVK builds repackaged for arm64ec (system32 = arm64ec, syswow64 = x86), published as `.wcp`
 * content archives, so they install through the same ContentsManager path as any other content.
 */
object ExtraComponents {
    data class Entry(
        val type: String,
        val id: String,
        val name: String,
        val url: String,
        val variant: String?,
        val arch: String?,
    )

    private const val ARM64EC_DXVK_BASE =
        "https://github.com/WinNative-Emu/Components/releases/download/Stable-DXVK-arm64ec"

    /** DXVK versions available as arm64ec builds, newest useful first. */
    val ARM64EC_DXVK: List<Entry> = listOf(
        entry("2.6.2-arm64ec-gplasync"),
        entry("2.7.1-arm64ec-gplasync"),
        entry("2.5.3-arm64ec-gplasync"),
        entry("2.3.1-arm64ec-gplasync"),
    )

    /** The build the dialog and the docs recommend for arm64ec Wine. */
    const val RECOMMENDED_ARM64EC_DXVK = "2.6.2-arm64ec-gplasync"

    val all: List<Entry> = ARM64EC_DXVK

    private fun entry(version: String): Entry = Entry(
        type = ManifestContentTypes.DXVK,
        id = version,
        name = "Dxvk-$version.wcp",
        url = "$ARM64EC_DXVK_BASE/Dxvk-$version.wcp",
        variant = "bionic",
        arch = "arm64ec",
    )

    fun find(id: String?): Entry? = all.firstOrNull { it.id == id }

    fun isArm64ecComponent(id: String?): Boolean = find(id) != null

    /** True for the `dxvk-<version>` strings the launch path hands around. */
    fun isArm64ecDxWrapper(dxWrapper: String?): Boolean {
        val value = dxWrapper.orEmpty()
        if (!value.startsWith("dxvk", ignoreCase = true)) return false
        return isArm64ecComponent(value.substringAfter("dxvk-", "").ifEmpty { value })
    }

    fun contentTypeOf(entry: Entry): ContentProfile.ContentType = when (entry.type) {
        ManifestContentTypes.DXVK -> ContentProfile.ContentType.CONTENT_TYPE_DXVK
        ManifestContentTypes.VKD3D -> ContentProfile.ContentType.CONTENT_TYPE_VKD3D
        else -> ContentProfile.ContentType.CONTENT_TYPE_DXVK
    }

    /** The additions, in the shape [ManifestRepository] merges into what it fetched. */
    fun manifestAdditions(): Map<String, List<ManifestEntry>> =
        all.groupBy { it.type }.mapValues { (_, entries) ->
            entries.map { ManifestEntry(id = it.id, name = it.name, url = it.url, variant = it.variant, arch = it.arch) }
        }
}
