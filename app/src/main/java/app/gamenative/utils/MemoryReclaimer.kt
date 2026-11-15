package app.gamenative.utils

import android.content.Context
import android.util.Log
import coil.ImageLoader
import timber.log.Timber

/**
 * Gives memory back to the device while a game is running.
 *
 * A game session holds the whole phone's memory budget: the game, Wine, the translation layer and
 * the DXVK heaps all live in the same pot as this app. When Android starts sending
 * `onTrimMemory(RUNNING_LOW / RUNNING_CRITICAL)` it is telling us the low-memory killer is close,
 * so everything this app is holding that is not needed during a session - chiefly the cover art
 * bitmaps behind the image loader - is dropped on the spot.
 *
 * The image loader is created inside a composition, so it is registered here when it is built.
 */
object MemoryReclaimer {
    private const val TAG = "MemoryReclaimer"

    @Volatile
    private var imageLoader: ImageLoader? = null

    /** Called by the UI that builds the loader; the reference is weak in spirit, not in type. */
    fun attachImageLoader(loader: ImageLoader?) {
        imageLoader = loader
    }

    /** Drops every cache this app can regenerate, and logs how much heap it freed. */
    fun reclaim(context: Context): Long {
        val heapBefore = usedHeapBytes()
        var dropped = false

        runCatching { imageLoader?.memoryCache?.clear() }
            .onSuccess { dropped = imageLoader?.memoryCache != null }
            .onFailure { Timber.tag(TAG).w(it, "image cache could not be cleared") }

        // Recycled bitmaps and dropped references still have to be collected to matter.
        runCatching { System.gc() }

        val heapAfter = usedHeapBytes()
        val freed = (heapBefore - heapAfter).coerceAtLeast(0L)
        PersistentLog.write(
            Log.INFO,
            TAG,
            "reclaim: imageCache=${if (dropped) "cleared" else "absent"} " +
                "heap ${heapBefore / 1_048_576}MB -> ${heapAfter / 1_048_576}MB " +
                "(free RAM ${MemoryBudget.freeRamMb(context)}MB)",
        )
        return freed
    }

    private fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}
