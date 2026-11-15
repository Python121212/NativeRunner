package app.gamenative.powercontrol.autotuning

import android.os.Build

/**
 * Devices whose power control settings were measured and tuned by hand. The list decides the
 * defaults a fresh profile starts with, never whether a feature is available: availability comes
 * from the driver and from the nodes it can reach.
 */
object DeviceGate {
    const val MODEL_RETROID_POCKET_6 = "retroid pocket 6"
    const val MODEL_RETROID_POCKET_NOVA = "retroid pocket nova"
    const val MODEL_ODIN_3 = "odin3"
    /** Google Pixel 9a (Tensor G4, Mali-G715, 64-bit only). */
    const val MODEL_PIXEL_9A = "pixel 9a"

    private val testedModels = arrayOf(
        MODEL_RETROID_POCKET_6,
        MODEL_RETROID_POCKET_NOVA,
        MODEL_PIXEL_9A,
    )

    /** Fanless handsets: power control is still tuned for them, fan control is not. */
    private val fanlessModels = arrayOf(
        MODEL_PIXEL_9A,
    )

    /** True when the device has a fan this app can drive. */
    fun hasFanControl(model: String = Build.MODEL ?: ""): Boolean {
        val normalized = normalize(model)
        return isDeviceSupported(model) && fanlessModels.none { normalized.contains(it) }
    }

    /** True on a Google Pixel 9a, the reference device for the 64-bit-only tuning. */
    fun isPixel9A(model: String = Build.MODEL ?: ""): Boolean =
        normalize(model).contains(MODEL_PIXEL_9A)

    fun isDeviceSupported(model: String = Build.MODEL ?: ""): Boolean {
        val normalized = normalize(model)
        return testedModels.any { normalized.contains(it) }
    }

    fun normalizedModel(model: String = Build.MODEL ?: ""): String {
        return normalize(model)
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
