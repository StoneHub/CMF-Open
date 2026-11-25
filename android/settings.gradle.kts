pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Versions live here so they are only resolved by the modules that apply them.
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "cmf-companion"
include(":protocol")
// The Android module needs the Android SDK. `-PprotocolOnly` builds and tests the
// pure-Kotlin protocol layer on any JDK.
if (!providers.gradleProperty("protocolOnly").isPresent) include(":app")
