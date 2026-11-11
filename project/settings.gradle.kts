pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android (the wireless-debugging ADB client) is published
        // through JitPack only.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "AzmAdmin"
include(":app")
include(":client")
