package app.gamenative.utils

import app.gamenative.BuildConfig

/**
 * Everything that has to change when the application id changes.
 *
 * NativeRunner installs as `app.nativerunner` so it can sit next to the official GameNative
 * (`app.gamenative`), which keeps the JNI method table (`Java_app_gamenative_*`) and the Kotlin
 * package namespace unchanged. Code must therefore never hard-code `app.gamenative` for anything
 * that is really a *package id* — paths under `/data/data/…`, intent actions, URI schemes and
 * component names all follow the installed id, while the namespace does not.
 *
 * These are `const val` so they can be used in `when` branches and annotations; the values come
 * from BuildConfig, which is generated per application id (including the `.gold` suffix).
 */
object AppIdentity {

    /** The installed package id, e.g. `app.nativerunner`. */
    const val PACKAGE: String = BuildConfig.APPLICATION_ID

    /** Prefix used by this app's own intent actions: `<package>.LAUNCH_GAME`, `<package>.AMAZON_SYNC_LIBRARY`, … */
    const val ACTION_PREFIX: String = PACKAGE

    /**
     * Scheme for this app's own deep links (`<package>://run`).
     *
     * A package id is a valid URI scheme, so the scheme follows the id: the official build keeps
     * `gamenative://` for itself. The Nexus OAuth redirect and the Discord relay link are *not*
     * covered by this — both are registered outside the app (see NexusOAuthConfig.REDIRECT_URI)
     * and stay on the fixed `app.gamenative` / `gamenative` values.
     */
    const val URI_SCHEME: String = PACKAGE

    /**
     * Namespace of the compiled classes. Relative names in the manifest (`.MainActivity`,
     * `.MainActivityAliasDefault`, …) are resolved against *this*, not against the application
     * id, so component names keep the `app.gamenative.` prefix in every build — the same reason
     * the `.gold` variant never needed its own component names.
     */
    const val NAMESPACE: String = "app.gamenative"

    /** `<namespace>.MainActivityAliasDefault` — the alias declared in the manifest. */
    fun mainActivityAlias(alt: Boolean): String =
        "$NAMESPACE.MainActivityAlias${if (alt) "Alt" else "Default"}"
}
