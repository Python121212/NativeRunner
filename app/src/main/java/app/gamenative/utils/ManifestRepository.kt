package app.gamenative.utils

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import timber.log.Timber

object ManifestRepository {
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/utkarshdalal/GameNative/refs/heads/master/manifest.json"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Everything upstream lists plus the components this build adds locally (see
     * [ExtraComponents]): the manifest URL is not ours to change, but the app still has to be able
     * to offer an arm64ec DXVK to an arm64ec container.
     */
    suspend fun loadManifest(context: Context): ManifestData = withLocalExtras(loadRemoteManifest(context))

    private fun withLocalExtras(data: ManifestData): ManifestData {
        val additions = ExtraComponents.manifestAdditions()
        if (additions.isEmpty()) return data
        val items = data.items.toMutableMap()
        additions.forEach { (type, entries) ->
            val known = items[type].orEmpty().map { it.id }.toSet()
            val missing = entries.filterNot { it.id in known }
            if (missing.isNotEmpty()) items[type] = items[type].orEmpty() + missing
        }
        return data.copy(items = items)
    }

    private suspend fun loadRemoteManifest(context: Context): ManifestData {
        if (BuildConfig.DEBUG) {
            readLocalManifest(context)?.let {
                Timber.i("ManifestRepository: using local debug manifest")
                return it
            }
        }

        val cachedJson = PrefManager.componentManifestJson
        val cachedManifest = parseManifest(cachedJson) ?: ManifestData.empty()
        val lastFetchedAt = PrefManager.componentManifestFetchedAt
        val isStale = System.currentTimeMillis() - lastFetchedAt >= ONE_DAY_MS

        if (cachedJson.isNotEmpty() && !isStale) {
            return cachedManifest
        }

        val fetched = fetchManifestJson()
        if (fetched != null) {
            val parsed = parseManifest(fetched)
            if (parsed != null) {
                val now = System.currentTimeMillis()
                PrefManager.componentManifestJson = fetched
                PrefManager.componentManifestFetchedAt = now
                return parsed
            }
        }

        return cachedManifest
    }

    private suspend fun fetchManifestJson(): String? = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder().url(MANIFEST_URL).build()
        Net.http.newCall(request).execute().use { response ->
            response.takeIf { it.isSuccessful }?.body?.string()
        }
    } catch (e: Exception) {
        Timber.e(e, "ManifestRepository: fetch failed")
        null
    }
}

    private fun readLocalManifest(context: Context): ManifestData? = try {
        context.assets.open("manifest.json").bufferedReader().use { parseManifest(it.readText()) }
    } catch (e: Exception) {
        null
    }

    fun parseManifest(jsonString: String?): ManifestData? {
        if (jsonString.isNullOrBlank()) return null
        return try {
            json.decodeFromString<ManifestData>(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "ManifestRepository: parse failed")
            null
        }
    }
}
