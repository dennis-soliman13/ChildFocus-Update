// android/settings.gradle.kts

pluginManagement {
    repositories {
        // No content{} filters here — filters can silently block plugin
        // marker artifacts whose group path doesn't match exactly.
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
    }
}

rootProject.name = "ChildFocus"
include(":app")