package app.gamenative.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

/**
 * What this installation actually is: the version the package manager has, who signed the APK it
 * came from, and which app installed it.
 *
 * An "app not installed" failure on update is decided entirely by the signature of the installed
 * APK and the one being installed, and there is no way to see that from the phone's settings. Both
 * the settings screen and the exported log bundle therefore carry this, so a failed update can be
 * told apart from a corrupt download without a cable.
 */
object InstallIdentity {
    data class Info(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val signerSha256: String,
        val installer: String,
    ) {
        fun summary(): String = buildString {
            append("$versionName ($versionCode)")
            append(" / $signerSha256")
        }
    }

    private var cached: Info? = null

    fun of(context: Context): Info = cached ?: build(context).also { cached = it }

    private fun build(context: Context): Info {
        val pm = context.packageManager
        val packageName = context.packageName

        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        }.getOrNull()

        val versionName = packageInfo?.versionName ?: "?"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            (packageInfo?.versionCode ?: 0).toLong()
        }

        return Info(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            signerSha256 = signerSha256(context),
            installer = installerOf(context),
        )
    }

    /** SHA-256 of the signing certificate, in the same form `apksigner --print-certs` prints. */
    fun signerSha256(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "n/a (API < 28)"
        return runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signatures = info.signingInfo?.apkContentsSigners ?: return "unknown"
            val signature = signatures.firstOrNull() ?: return "unknown"
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
        }.getOrElse { "unknown (${it.javaClass.simpleName})" }
    }

    private fun installerOf(context: Context): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName)
                .installingPackageName ?: "sideload / unknown"
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName) ?: "sideload / unknown"
        }
    }.getOrDefault("unknown")
}
