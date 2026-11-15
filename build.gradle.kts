// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.dagger.hilt) apply false
    alias(libs.plugins.jetbrains.jvm) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.jetbrains.serialization) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.secrets.gradle) apply false
}

// CI speed switch: by default changing/dynamic modules are re-resolved on every
// build. CI passes -PciCacheDependencies=true so it can reuse the dependency
// cache between runs instead of hitting the network on every resolution.
val ciCacheDependencies: Boolean =
    (findProperty("ciCacheDependencies") as String?)?.toBoolean() == true

configurations.configureEach {
    if (ciCacheDependencies) {
        resolutionStrategy.cacheChangingModulesFor(24, TimeUnit.HOURS)
        resolutionStrategy.cacheDynamicVersionsFor(24, TimeUnit.HOURS)
    } else {
        resolutionStrategy.cacheChangingModulesFor(0, TimeUnit.SECONDS)
        resolutionStrategy.cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
    }
}

// CI speed switch: release builds run the "vital lint" gate, which costs minutes
// and is irrelevant when the goal is an APK. Opt in with -PskipReleaseLint=true.
if ((findProperty("skipReleaseLint") as String?)?.toBoolean() == true) {
    allprojects {
        tasks.matching { it.name.startsWith("lintVital") }.configureEach {
            enabled = false
        }
    }
}
